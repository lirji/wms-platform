package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.RuntimeInboxMapper;
import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 使用应用实际Inbox迁移与Mapper验证效果回滚、毒消息隔离及进程恢复后的幂等。 */
class RuntimeInboxIT {
    @Test
    void businessEffectAndCompletionCommitTogetherAndConflictsAreIsolated() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4.11")) {
            mysql.start();
            var source = new com.mysql.cj.jdbc.MysqlDataSource();
            source.setUrl(mysql.getJdbcUrl()); source.setUser(mysql.getUsername()); source.setPassword(mysql.getPassword());
            try (var connection = source.getConnection(); var sql = connection.createStatement()) {
                sql.execute(java.nio.file.Files.readString(java.nio.file.Path.of("..", "wms-inventory", "src", "main", "resources", "db", "migration", "V024__runtime_message_inbox.sql")));
                sql.execute("CREATE TABLE test_effect(id VARCHAR(64) PRIMARY KEY COMMENT '原始业务事件') COMMENT='仅本测试的业务效果'");
            }
            var configuration = new Configuration(new Environment("runtime-inbox", new JdbcTransactionFactory(), source));
            configuration.addMapper(RuntimeInboxMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(configuration);
            var time = new AtomicReference<>(Instant.parse("2026-09-12T00:00:00Z"));
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return time.get(); }
            };
            var inbox = new RuntimeInbox(sessions, Map.of("inbound.commands", "wms-inbound"), clock);
            String body = new RuntimeMessage(1, "EVENT", "wms-inbound", "ENT", "WH", "StockCommandRequested", "LINE", 1,
                    time.get().toString(), "request-test", RuntimeMessage.JSON.readTree("{\"qty\":\"3\"}")).encode();
            inbox.persist(new ConsumerRecord<>("inbound.commands", 0, 0, "LINE", body));
            inbox.persist(new ConsumerRecord<>("inbound.commands", 0, 1, "LINE", body));
            inbox.persist(new ConsumerRecord<>("inbound.commands", 0, 2, "LINE", body.replace("3", "4")));
            inbox.persist(new ConsumerRecord<>("inbound.commands", 0, 3, "LINE", "{invalid"));
            RuntimeInbox.Handler effect = (session, message) -> {
                try (var insert = session.getConnection().prepareStatement("INSERT INTO test_effect(id) VALUES (?)")) {
                    insert.setString(1, message.eventId()); insert.executeUpdate();
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            };
            assertTrue(inbox.processNext((session, message) -> { effect.apply(session, message); throw new IllegalStateException("模拟提交前崩溃"); }));
            try (var connection = source.getConnection(); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM test_effect")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            time.set(time.get().plusSeconds(10));
            var restarted = new RuntimeInbox(sessions, Map.of("inbound.commands", "wms-inbound"), clock);
            assertTrue(restarted.processNext(effect));
            assertFalse(restarted.processNext(effect));
            try (var connection = source.getConnection(); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT status,COUNT(*) FROM runtime_message_inbox GROUP BY status ORDER BY status")) {
                assertTrue(rows.next()); assertEquals("DONE", rows.getString(1)); assertEquals(1, rows.getInt(2));
                assertTrue(rows.next()); assertEquals("ISOLATED", rows.getString(1)); assertEquals(2, rows.getInt(2));
            }
            try (var connection = source.getConnection(); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM test_effect")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        }
    }
}
