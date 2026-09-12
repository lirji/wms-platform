package com.lrj.wms.inventory;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.*;
import com.lrj.wms.runtime.messaging.KafkaInboxConsumer;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 生产Bean装配：权威写入→Outbox→真实Kafka→持久化Inbox→投影，不手动调用投影服务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class InventoryMessagingIT {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static {
        MYSQL.start(); KAFKA.start();
        var properties = new Properties(); properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        try (var admin = AdminClient.create(properties)) {
            admin.createTopics(List.of(new NewTopic("wms.test.inventory.events", 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("wms.inventory.datasource.url", MYSQL::getJdbcUrl);
        properties.add("wms.inventory.datasource.username", MYSQL::getUsername);
        properties.add("wms.inventory.datasource.password", MYSQL::getPassword);
        properties.add("wms.oidc.issuer", () -> "http://issuer.invalid/test");
        properties.add("wms.oidc.client-id", () -> "wms-platform");
        properties.add("wms.messaging.enabled", () -> "true");
        properties.add("wms.messaging.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("wms.messaging.topic-prefix", () -> "wms.test");
    }
    @Autowired SqlSessionFactory sessions;
    @Autowired DataSource source;
    @Autowired KafkaInboxConsumer consumer;
    @Autowired com.lrj.wms.runtime.observability.RuntimeReadiness readiness;

    @AfterAll static void cleanup() { KAFKA.stop(); MYSQL.stop(); }

    @Test void committedInventoryAutomaticallyReachesProjectionWithOriginalTimestamp() throws Exception {
        Instant occurred = Instant.parse("2026-09-12T01:00:00Z");
        Clock clock = Clock.fixed(occurred, ZoneOffset.UTC);
        try (var session = sessions.openSession(false)) {
            var masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-MQ", "ENT-MQ", "MQ", "消息测试仓", "UTC");
            masterdata.createLocation("LOC-MQ", "GATE-MQ", "ENT-MQ", "WH-MQ", "MQ-01", "A", "STORAGE", new BigDecimal("100"), "EA");
            masterdata.createSku(SkuPolicy.create("SKU-MQ", "ENT-MQ", "MQ", "消息商品", "EA", 0, false, false, false, 1,
                    MasterdataCodes.STATE_ACTIVE), "UNIT-MQ");
            var inventory = new InventoryApplicationService(session, clock);
            var bucket = StockBucketKey.of("ENT-MQ", "WH-MQ", "OWNER", "LOC-MQ", "SKU-MQ", MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            inventory.receive("ENT-MQ", "WH-MQ", "MQ-OP-1", "DOC", "ACTOR", bucket, Quantity.parse("7", 0));
            inventory.receive("ENT-MQ", "WH-MQ", "MQ-OP-2", "DOC", "ACTOR", bucket, Quantity.parse("2", 0));
            session.commit();
        }
        var jdbc = new JdbcTemplate(source);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && jdbc.queryForObject("SELECT COUNT(*) FROM inventory_view WHERE enterprise_id='ENT-MQ' AND on_hand_qty=9", Integer.class) == 0) {
            Thread.sleep(100);
        }
        assertTrue(consumer.isRunning());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE status='DONE'", Integer.class));
        var view = jdbc.queryForMap("SELECT on_hand_qty,source_version,as_of FROM inventory_view WHERE enterprise_id='ENT-MQ'");
        assertEquals(0, new BigDecimal("9").compareTo((BigDecimal) view.get("on_hand_qty")));
        assertEquals(2L, ((Number) view.get("source_version")).longValue());
        assertEquals(occurred, ExpiryPolicy.instantOf(view.get("as_of")));
        assertEquals("UP", readiness.health().getStatus().getCode());
        // 只暂停本测试的专属broker；权威事务可落Outbox，不能伪报已经投递。
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            try (var session = sessions.openSession(false)) {
                new InventoryApplicationService(session, clock).receive("ENT-MQ", "WH-MQ", "MQ-OP-3", "DOC", "ACTOR",
                        StockBucketKey.of("ENT-MQ", "WH-MQ", "OWNER", "LOC-MQ", "SKU-MQ", MasterdataCodes.NO_LOT,
                                InventoryCodes.QUALITY_GOOD), Quantity.parse("1", 0));
                session.commit();
            }
            long unhealthyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (System.nanoTime() < unhealthyDeadline && "UP".equals(readiness.health().getStatus().getCode())) Thread.sleep(100);
            assertEquals("DOWN", readiness.health().getStatus().getCode());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='MQ-OP-3' AND status='PUBLISHED'", Integer.class));
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < recoveryDeadline && jdbc.queryForObject("SELECT COUNT(*) FROM inventory_view WHERE enterprise_id='ENT-MQ' AND on_hand_qty=10", Integer.class) == 0) Thread.sleep(100);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inventory_view WHERE enterprise_id='ENT-MQ' AND on_hand_qty=10", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger", Integer.class));
    }
}
