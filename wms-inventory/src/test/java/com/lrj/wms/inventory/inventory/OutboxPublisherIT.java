package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
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

/** S2-04 领取/发布/重试/隔离。不是 Kafka 投递或 AC-05 崩溃注入。 */
class OutboxPublisherIT {
    private static final Instant NOW = Instant.parse("2026-09-11T01:00:00Z");

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
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, Clock.fixed(NOW, ZoneOffset.UTC));
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void publishRetryIsolateAndReclaimExpiredLease() {
        receive("OP-PUB", "SKU-PUB");
        List<OutboxRecord> sent = new ArrayList<>();
        OutboxPublisher publisher = new OutboxPublisher(sessions, sent::add, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(1, publisher.publishDue());
        assertEquals(1, sent.size());
        assertEquals(InventoryCodes.EVENT_BALANCE_CHANGED, sent.getFirst().eventType());
        assertEquals(InventoryCodes.OUTBOX_PUBLISHED,
                jdbc.queryForObject("SELECT status FROM outbox_event WHERE operation_id='OP-PUB'", String.class));
        assertEquals(0, publisher.publishDue());

        receive("OP-RETRY", "SKU-RETRY");
        AtomicInteger attempts = new AtomicInteger();
        MutableClock clock = new MutableClock(NOW);
        OutboxPublisher failing = new OutboxPublisher(sessions, record -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
        }, clock);
        assertEquals(0, failing.publishDue());
        assertEquals(InventoryCodes.OUTBOX_PENDING,
                jdbc.queryForObject("SELECT status FROM outbox_event WHERE operation_id='OP-RETRY'", String.class));
        assertEquals(0, failing.publishDue());
        clock.advance(Duration.ofSeconds(3));
        assertEquals(1, failing.publishDue());
        assertEquals(InventoryCodes.OUTBOX_PUBLISHED,
                jdbc.queryForObject("SELECT status FROM outbox_event WHERE operation_id='OP-RETRY'", String.class));

        receive("OP-ISO", "SKU-ISO");
        OutboxPublisher isolating = new OutboxPublisher(sessions,
                record -> {
                    throw new OutboxIsolateException("poison");
                }, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(0, isolating.publishDue());
        assertEquals(InventoryCodes.OUTBOX_ISOLATED,
                jdbc.queryForObject("SELECT status FROM outbox_event WHERE operation_id='OP-ISO'", String.class));
        assertEquals(0, isolating.publishDue());

        receive("OP-LEASE", "SKU-LEASE");
        jdbc.update("UPDATE outbox_event SET status='CLAIMED', claim_epoch=1, "
                + "lease_until=TIMESTAMP '2026-09-11 00:59:00' WHERE operation_id='OP-LEASE'");
        List<OutboxRecord> reclaimed = new ArrayList<>();
        OutboxPublisher reclaim = new OutboxPublisher(sessions, reclaimed::add, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(1, reclaim.publishDue());
        assertEquals(1, reclaimed.size());
        assertEquals(InventoryCodes.OUTBOX_PUBLISHED,
                jdbc.queryForObject("SELECT status FROM outbox_event WHERE operation_id='OP-LEASE'", String.class));
    }

    private void receive(String operationId, String skuId) {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", skuId, MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService service = new InventoryApplicationService(session,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            service.receive("ENT-1", "WH-A", operationId, "DOC", "ACTOR", bucket, Quantity.parse("1", 0));
            session.commit();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
