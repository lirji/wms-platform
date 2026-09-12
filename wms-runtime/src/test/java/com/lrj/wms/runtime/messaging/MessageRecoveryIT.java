package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.MessageRecoveryMapper;
import com.lrj.wms.runtime.messaging.persistence.RuntimeInboxMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 人工重放必须保留原消息与代际，真实事务验证预算恢复、审计、授权范围及原子性。 */
class MessageRecoveryIT {
    @Test void auditedRetryPreservesIdentityAndFencingAndRejectsUntrustedMessages() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4.11")) {
            mysql.start();
            var source = new com.mysql.cj.jdbc.MysqlDataSource();
            source.setUrl(mysql.getJdbcUrl()); source.setUser(mysql.getUsername()); source.setPassword(mysql.getPassword());
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                for (String file : List.of("V024__runtime_message_inbox.sql", "V005__outbox.sql", "V028__message_recovery.sql")) {
                    for (String sql : Files.readString(Path.of("..", "wms-inventory", "src", "main", "resources", "db", "migration", file)).split(";")) {
                        if (!sql.isBlank()) statement.execute(sql);
                    }
                }
                statement.execute("CREATE TABLE test_recovered_effect(id VARCHAR(64) PRIMARY KEY COMMENT '原始消息标识') COMMENT='测试消息恢复效果'");
            }
            var configuration = new Configuration(new Environment("recovery", new JdbcTransactionFactory(), source));
            configuration.addMapper(RuntimeInboxMapper.class); configuration.addMapper(MessageRecoveryMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(configuration);
            var jdbc = new JdbcTemplate(source);
            var time = new AtomicReference<>(Instant.parse("2026-09-12T00:00:00Z"));
            Clock clock = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return this; }
                public Instant instant() { return time.get(); }
            };
            var inbox = new RuntimeInbox(sessions, Map.of("commands", "wms-inbound"), clock);
            var message = new RuntimeMessage(1, "ORIGINAL", "wms-inbound", "ENT", "WH", "TEST", "LINE", 1,
                    time.get().toString(), "original-request", RuntimeMessage.JSON.readTree("{\"qty\":\"3\"}"));
            inbox.persist(new ConsumerRecord<>("commands", 0, 0, "LINE", message.encode()));
            for (int i = 0; i < 8; i++) {
                assertTrue(inbox.processNext((session, event) -> { throw new IllegalStateException("可恢复系统故障"); }));
                time.set(time.get().plusSeconds(120));
            }
            assertFalse(inbox.processNext((session, event) -> fail("预算已耗尽")));
            String id = jdbc.queryForObject("SELECT id FROM runtime_message_inbox WHERE event_key=?", String.class, message.identity());
            String originalBody = jdbc.queryForObject("SELECT payload FROM runtime_message_inbox WHERE id=?", String.class, id);
            var recovery = new MessageRecoveryService(sessions, MessageQueueMetrics.Queue.INVENTORY_OUTBOX, inbox, clock);
            assertEquals("MESSAGE_NOT_FOUND", assertThrows(MessageRecoveryException.class,
                    () -> recovery.retry("ENT", "OTHER-WH", "INBOX", id, "RETRY", 8, "依赖已恢复", "ADMIN")).code());
            assertEquals("MESSAGE_STATE_CONFLICT", assertThrows(MessageRecoveryException.class,
                    () -> recovery.retry("ENT", "WH", "INBOX", id, "RETRY", 7, "依赖已恢复", "ADMIN")).code());
            // 审计写入失败时队列状态必须回滚，不能只恢复预算而漏掉操作者证据。
            jdbc.execute("ALTER TABLE message_recovery_audit ADD CONSTRAINT reject_test_audit CHECK (actor_id <> 'ADMIN')");
            assertThrows(RuntimeException.class, () -> recovery.retry("ENT", "WH", "INBOX", id, "RETRY", 8, "依赖已恢复", "ADMIN"));
            assertEquals("ISOLATED", jdbc.queryForObject("SELECT status FROM runtime_message_inbox WHERE id=?", String.class, id));
            jdbc.execute("ALTER TABLE message_recovery_audit DROP CHECK reject_test_audit");
            var accepted = recovery.retry("ENT", "WH", "INBOX", id, "RETRY", 8, "依赖已恢复", "ADMIN");
            assertEquals(false, accepted.get("replayed"));
            assertEquals(8L, jdbc.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE id=?", Long.class, id));
            assertEquals(8L, jdbc.queryForObject("SELECT retry_base_epoch FROM runtime_message_inbox WHERE id=?", Long.class, id));
            try (var session = sessions.openSession(false)) {
                assertEquals(0, session.getMapper(RuntimeInboxMapper.class).finish(id, 8, "DONE", null,
                        Timestamp.from(time.get()), Timestamp.from(time.get())));
                session.commit();
            }
            assertTrue(inbox.processNext((session, event) -> {
                try (var insert = session.getConnection().prepareStatement("INSERT INTO test_recovered_effect(id) VALUES (?)")) {
                    insert.setString(1, event.eventId()); insert.executeUpdate();
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            }));
            assertEquals(9L, jdbc.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE id=?", Long.class, id));
            assertEquals(originalBody, jdbc.queryForObject("SELECT payload FROM runtime_message_inbox WHERE id=?", String.class, id));
            assertEquals(true, recovery.retry("ENT", "WH", "INBOX", id, "RETRY", 8, "依赖已恢复", "ADMIN").get("replayed"));
            assertEquals("RECOVERY_KEY_CONFLICT", assertThrows(MessageRecoveryException.class,
                    () -> recovery.retry("ENT", "WH", "INBOX", id, "RETRY", 8, "修改理由", "ADMIN")).code());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_recovered_effect", Integer.class));
            assertEquals("ADMIN", jdbc.queryForObject("SELECT actor_id FROM message_recovery_audit", String.class));
            inbox.persist(new ConsumerRecord<>("untrusted", 0, 1, "LINE", message.encode()));
            String poison = jdbc.queryForObject("SELECT id FROM runtime_message_inbox WHERE topic_name='untrusted'", String.class);
            assertEquals("MESSAGE_NOT_REPLAYABLE", assertThrows(MessageRecoveryException.class,
                    () -> recovery.retry("ENT", "WH", "INBOX", poison, "BAD-RETRY", 0, "不能越权恢复", "ADMIN")).code());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM message_recovery_audit", Integer.class));
            @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) recovery.list("ENT", "WH", "INBOX", "ISOLATED", 1, null).get("items");
            assertEquals(1, rows.size()); assertFalse(rows.getFirst().containsKey("payload"));
            @SuppressWarnings("unchecked") var other = (List<Map<String, Object>>) recovery.list("ENT", "OTHER-WH", "INBOX", "ISOLATED", 1, null).get("items");
            assertTrue(other.isEmpty());
        }
    }
}
