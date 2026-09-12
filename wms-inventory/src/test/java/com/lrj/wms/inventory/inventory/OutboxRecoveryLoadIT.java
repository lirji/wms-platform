package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S7-05：消息恢复有界批量，重放不重复投递。 */
class OutboxRecoveryLoadIT {
    private static final Instant NOW = Instant.parse("2026-09-12T09:30:00Z");
    private static final int TOTAL = 80;
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("outbox-load", new JdbcTransactionFactory(), source));
        config.addMapper(OutboxMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Timestamp now = Timestamp.from(NOW);
        for (int i = 0; i < TOTAL; i++) {
            jdbc.update("INSERT INTO outbox_event (event_id, enterprise_id, warehouse_id, aggregate_type, aggregate_id, "
                    + "aggregate_version, event_type, operation_id, payload, status, claim_epoch, next_attempt_at, "
                    + "version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,0,?,0,?,?)",
                    "EVT-" + i, "ENT-1", "WH-A", InventoryCodes.AGGREGATE_STOCK_BALANCE, "BAL-1", i + 1L,
                    InventoryCodes.EVENT_BALANCE_CHANGED, "OP-" + i, "{}", InventoryCodes.OUTBOX_PENDING, now, now, now);
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void recoveryStaysBoundedAndDoesNotRepublish() {
        List<OutboxRecord> sent = new CopyOnWriteArrayList<>();
        OutboxPublisher publisher = new OutboxPublisher(sessions, sent::add, Clock.fixed(NOW, ZoneOffset.UTC));
        int first = publisher.publishDue();
        assertEquals(OutboxPublisher.BATCH_SIZE, first);
        int second = publisher.publishDue();
        assertEquals(OutboxPublisher.BATCH_SIZE, second);
        int third = publisher.publishDue();
        assertEquals(TOTAL - 2 * OutboxPublisher.BATCH_SIZE, third);
        assertEquals(0, publisher.publishDue());
        assertEquals(TOTAL, sent.size());
        Set<String> ids = new HashSet<>();
        for (OutboxRecord record : sent) {
            assertTrue(ids.add(record.eventId()));
        }
        assertEquals(TOTAL, ids.size());
        assertEquals(Integer.valueOf(TOTAL), jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED'", Integer.class));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','CLAIMED')", Integer.class));
        assertEquals(0, publisher.publishDue());
        assertEquals(TOTAL, sent.size());
    }
}
