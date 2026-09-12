package com.lrj.wms.runtime.messaging;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实broker与MySQL验证：提交Inbox前失败不得前移位点，提交后重复投递只有一行。 */
class KafkaMessagingIT {
    @Test
    void retriesFailedPersistenceAndCommitsOnlyDurableInbox() throws Exception {
        try (var kafka = new KafkaContainer("apache/kafka:3.8.0"); var mysql = new MySQLContainer("mysql:8.4.11")) {
            kafka.start();
            mysql.start();
            var source = new com.mysql.cj.jdbc.MysqlDataSource();
            source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC")); source.setUser(mysql.getUsername()); source.setPassword(mysql.getPassword());
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE test_inbox (id VARCHAR(64) PRIMARY KEY COMMENT '测试事件身份', payload JSON NOT NULL COMMENT '原始消息') COMMENT='仅本测试的持久化收件箱'");
            }
            String topic = "wms.it." + UUID.randomUUID();
            var settings = new KafkaSettings(true, kafka.getBootstrapServers(), "wms.it", "PLAINTEXT", "", "");
            try (var admin = AdminClient.create(settings.connection())) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            }
            var attempts = new AtomicInteger();
            var received = new CountDownLatch(2);
            var consumer = new KafkaInboxConsumer(settings, "group-" + UUID.randomUUID(), List.of(topic), record -> {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("测试提交前断连");
                try (var connection = source.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var insert = connection.prepareStatement("INSERT INTO test_inbox(id,payload) VALUES (?,?) ON DUPLICATE KEY UPDATE id=id")) {
                        insert.setString(1, record.key()); insert.setString(2, record.value()); insert.executeUpdate();
                    }
                    connection.commit();
                    received.countDown();
                } catch (java.sql.SQLException unavailable) { throw new IllegalStateException(unavailable); }
            });
            consumer.start();
            try (var producer = new KafkaMessagePublisher(settings, "test-publisher")) {
                producer.publish(topic, "same-event", "{\"value\":1}");
                // 使用LZ4编码的另一投递，验证补丁版传递依赖与旧broker/正式消费者互通。
                var compressedProperties = settings.connection();
                compressedProperties.put("compression.type", "lz4");
                compressedProperties.put("acks", "all");
                compressedProperties.put("max.block.ms", "1000");
                try (var compressed = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(compressedProperties,
                        new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.StringSerializer())) {
                    compressed.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, "same-event", "{\"value\":1}")).get(15, TimeUnit.SECONDS);
                }
                assertTrue(received.await(30, TimeUnit.SECONDS));
                assertTrue(attempts.get() >= 3);
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                        var result = statement.executeQuery("SELECT COUNT(*) FROM test_inbox")) {
                    assertTrue(result.next()); assertEquals(1, result.getInt(1));
                }
            } finally { consumer.stop(); }
            assertFalse(consumer.isRunning());
        }
    }
}
