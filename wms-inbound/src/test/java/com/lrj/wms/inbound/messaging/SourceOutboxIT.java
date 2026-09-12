package com.lrj.wms.inbound.messaging;

import com.lrj.wms.inbound.protocol.SourceMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptService;
import com.lrj.wms.runtime.messaging.*;
import com.lrj.wms.runtime.messaging.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 实际T1/迁移/Mapper/发布器到真实Kafka，验证原始上下文及旧minimal隔离。尚不替代库存T2。 */
class SourceOutboxIT {
    @Test void publishesOriginalSourceFactAndIsolatesMissingLegacyContext() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4.11"); var kafka = new KafkaContainer("apache/kafka:3.8.0")) {
            mysql.start(); kafka.start();
            var source = new com.mysql.cj.jdbc.MysqlDataSource();
            source.setUrl(mysql.getJdbcUrl()); source.setUser(mysql.getUsername()); source.setPassword(mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var config = new Configuration(new Environment("source", new JdbcTransactionFactory(), source));
            config.addMapper(SourceMapper.class); config.addMapper(InboundReceiptMapper.class);
            config.addMapper(SourceContextMapper.class); config.addMapper(SourceOutboxMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(config);
            Instant original = Instant.parse("2026-09-12T01:00:00Z");
            Clock clock = Clock.fixed(original, ZoneOffset.UTC);
            try (var session = sessions.openSession(false)) {
                var service = new InboundReceiptService(session, clock);
                for (String name : List.of("VALID", "LEGACY")) {
                    service.createOrder("ENT", "WH", "ORDER-" + name, "ERP", name, "OWNER",
                            List.of(Map.of("lineId", "LINE-" + name, "externalLineId", "L1", "skuId", "SKU", "expectedQty", new BigDecimal("3"), "unit", "EA")));
                    var result = service.receive("ENT", "WH", "ORDER-" + name, "LINE-" + name, "CMD-" + name, "PART", "ORIGINAL-ACTOR", new BigDecimal("3"));
                    if (name.equals("VALID")) service.bindReceiveContext("ENT", "WH", "ORDER-VALID", "LINE-VALID", result, "RECEIVING", "NO_LOT");
                }
                session.commit();
            }
            var settings = new KafkaSettings(true, kafka.getBootstrapServers(), "wms.it", "PLAINTEXT", "", "");
            String topic = "wms.it.inbound.commands";
            try (var admin = AdminClient.create(settings.connection())) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            }
            try (var sender = new KafkaMessagePublisher(settings, "source-test")) {
                var outbox = new SourceOutboxPublisher(sessions, sender, "wms-inbound", "wms.it", Clock.offset(clock, Duration.ofHours(1)));
                assertEquals(1, outbox.publishDue());
                assertEquals(0, outbox.publishDue());
            }
            var properties = settings.connection();
            properties.put("group.id", "test-" + UUID.randomUUID()); properties.put("auto.offset.reset", "earliest");
            properties.put("enable.auto.commit", "false");
            try (var consumer = new KafkaConsumer<String, String>(properties, new StringDeserializer(), new StringDeserializer())) {
                consumer.subscribe(List.of(topic));
                RuntimeMessage found = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (found == null && System.nanoTime() < deadline) {
                    for (var record : consumer.poll(Duration.ofMillis(250))) {
                        assertNull(found); found = RuntimeMessage.parse(record.value());
                    }
                }
                assertNotNull(found);
                assertEquals(original.toString(), found.occurredAt());
                assertEquals("wms-inbound", found.sourceService());
                assertEquals("CMD-VALID", found.payload().path("commandId").asString());
                assertEquals("ORIGINAL-ACTOR", found.payload().path("actorId").asString());
                assertEquals("LINE-VALID", found.payload().path("factLineId").asString());
                assertEquals("HOLD", found.payload().path("postingContext").path("qualityCode").asString());
                assertEquals("ORDER-VALID", found.payload().path("postingContext").path("documentId").asString());
                assertFalse(found.payload().path("sourceExecutionId").asString().isBlank());
            }
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            assertEquals("ISOLATED", jdbc.queryForObject("SELECT status FROM source_outbox WHERE command_id='CMD-LEGACY'", String.class));
            assertEquals("LEGACY_COMMAND_CONTEXT_MISSING", jdbc.queryForObject("SELECT error_code FROM source_outbox WHERE command_id='CMD-LEGACY'", String.class));
            assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM source_outbox WHERE command_id='CMD-VALID'", String.class));
        }
    }
}
