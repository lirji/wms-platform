package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.MessageQueueMetricsMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真库验证积压上限、最旧年龄、隔离状态和监控故障时的旧快照可识别性。 */
class MessageQueueMetricsIT {
    @Test
    void boundedBacklogAndOldestAgeRemainVisibleWhenSamplingFails() throws Exception {
        var registry = new SimpleMeterRegistry();
        try (var mysql = new MySQLContainer("mysql:8.4.11")) {
            mysql.start();
            var source = new com.mysql.cj.jdbc.MysqlDataSource();
            source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC")); source.setUser(mysql.getUsername()); source.setPassword(mysql.getPassword());
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                for (String file : new String[]{"V024__runtime_message_inbox.sql", "V005__outbox.sql", "V027__message_queue_metrics.sql"}) {
                    for (String sql : Files.readString(Path.of("..", "wms-inventory", "src", "main", "resources", "db", "migration", file)).split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
            }
            Instant now = Instant.parse("2026-09-12T00:00:00Z");
            try (var connection = source.getConnection(); var insert = connection.prepareStatement(
                    "INSERT INTO runtime_message_inbox(id,topic_name,partition_no,offset_no,payload_hash,payload,status,next_attempt_at,created_at,updated_at) VALUES (?,'test',0,?,?,'{}',?,?,?,?)")) {
                for (int i = 0; i < 1006; i++) {
                    insert.setString(1, "METRIC-" + i); insert.setLong(2, i); insert.setString(3, "d".repeat(64));
                    insert.setString(4, i == 1005 ? "ISOLATED" : "PENDING");
                    for (int column = 5; column <= 7; column++) insert.setTimestamp(column, Timestamp.from(now.minusSeconds(120)));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            var configuration = new Configuration(new Environment("metrics", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(configuration);
            configuration.addMapper(MessageQueueMetricsMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(configuration);
            var time = new AtomicReference<>(now);
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return time.get(); }
            };
            var metrics = new MessageQueueMetrics(sessions, registry, MessageQueueMetrics.Queue.INVENTORY_OUTBOX, clock);
            assertEquals(0, registry.get("wms.messaging.sample.available").gauge().value());
            metrics.sampleDue();
            assertEquals(1001, gauge(registry, "wms.messaging.backlog", "PENDING"));
            assertEquals(1, gauge(registry, "wms.messaging.backlog.capped", "PENDING"));
            assertEquals(1, gauge(registry, "wms.messaging.backlog", "ISOLATED"));
            assertEquals(120, gauge(registry, "wms.messaging.oldest.age", "PENDING"));
            time.set(now.plusSeconds(7));
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("RENAME TABLE outbox_event TO test_offline_outbox");
            }
            // 抓取只读内存；采样异常不能发布已经半途更新的新快照或伪装零积压。
            assertEquals(127, gauge(registry, "wms.messaging.oldest.age", "PENDING"));
            assertThrows(RuntimeException.class, metrics::sampleDue);
            assertEquals(1001, gauge(registry, "wms.messaging.backlog", "PENDING"));
            assertEquals(7, registry.get("wms.messaging.sample.age").gauge().value());
            assertDoesNotThrow(metrics::sampleDue);
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("RENAME TABLE test_offline_outbox TO outbox_event");
                statement.execute("UPDATE runtime_message_inbox SET status='DONE' WHERE status='PENDING'");
            }
            time.set(now.plusSeconds(13));
            metrics.sampleDue();
            assertEquals(0, gauge(registry, "wms.messaging.backlog", "PENDING"));
            assertEquals(0, gauge(registry, "wms.messaging.oldest.age", "PENDING"));
            assertEquals(0, registry.get("wms.messaging.sample.age").gauge().value());
            assertTrue(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                    .allMatch(tag -> tag.getKey().equals("queue") || tag.getKey().equals("state")));
        } finally { registry.close(); }
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String state) {
        return registry.get(name).tags("queue", "INBOX", "state", state).gauge().value();
    }
}
