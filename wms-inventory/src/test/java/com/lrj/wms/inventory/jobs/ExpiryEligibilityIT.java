package com.lrj.wms.inventory.jobs;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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

/** S7-04：过期巡检只通知，不释放预占、不发明失效时刻。 */
class ExpiryEligibilityIT {
    private static final Instant RESERVE_AT = Instant.parse("2026-09-11T10:00:00Z");
    private static final Instant SWEEP_AT = Instant.parse("2026-09-12T10:00:00Z");
    private static final String DIGEST = "e".repeat(64);
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
        Configuration config = new Configuration(new Environment("expiry", new JdbcTransactionFactory(), source));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(ExpiryEligibilityMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(RESERVE_AT, ZoneOffset.UTC);
        SkuPolicy sku = SkuPolicy.create("SKU-EXP", "ENT-1", "SKU-EXP", "效期商品", "EA", 0, true, false, true, 1,
                MasterdataCodes.STATE_ACTIVE);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
            masterdata.createSku(sku, "UNIT-EXP");
            masterdata.createLot(sku, "LOT-NEAR", "WH-A", "OWNER-1", "NEAR", "ENT-1/OWNER-1/SKU-EXP/NEAR",
                    Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-12T10:00:00Z"), "2026-09-12", 1);
            masterdata.createLot(sku, "LOT-FAR", "WH-A", "OWNER-1", "FAR", "ENT-1/OWNER-1/SKU-EXP/FAR",
                    Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-10-01T10:00:00Z"), "2026-10-01", 1);
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-NEAR", "DOC", "ACTOR", bucket("LOT-NEAR"), Quantity.parse("5", 0));
            inventory.receive("ENT-1", "WH-A", "OP-FAR", "DOC", "ACTOR", bucket("LOT-FAR"), Quantity.parse("5", 0));
            inventory.reserve("ENT-1", "WH-A", "OP-RSV", "DOC", "ACTOR", "ALLOC-1", "ATT-1", "xid-1", 1L,
                    "ReservationTccAction", 1L, DIGEST, bucket("LOT-NEAR"), Quantity.parse("1", 0), "OL-1");
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
    void actualHandlerCommitsBoundedPagesAndDoesNotStarveLaterLots() {
        var clock = Clock.systemUTC();
        try (SqlSession session = sessions.openSession(false)) {
            var masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-BATCH", "ENT-1", "BATCH", "巡检批量仓", "UTC");
            var sku = SkuPolicy.create("SKU-EXP", "ENT-1", "SKU-EXP", "效期商品", "EA", 0, true, false, true, 1,
                    MasterdataCodes.STATE_ACTIVE);
            for (int i = 0; i < 201; i++) {
                masterdata.createLot(sku, "BATCH-" + i, "WH-BATCH", "OWNER-1", "BATCH-" + i,
                        "ENT-1/OWNER-1/SKU-EXP/BATCH-" + i, clock.instant().minusSeconds(259200),
                        clock.instant().minusSeconds(172800), "explicit-utc", 1);
            }
            session.commit();
        }
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("sqlSessionFactory", sessions);
        var handler = new InventoryCatalogJobs(beans.getBeanProvider(com.lrj.wms.inventory.tcc.TccReservationWatch.class),
                beans.getBeanProvider(SqlSessionFactory.class));
        com.xxl.job.core.context.XxlJobContext.setXxlJobContext(new com.xxl.job.core.context.XxlJobContext(
                1, "ENT-1,WH-BATCH,W-BATCH", 1, System.currentTimeMillis(), "", 0, 1));
        try {
            for (int pass = 1; pass <= 3; pass++) {
                handler.expiryEligibilitySweep();
                assertEquals(Math.min(pass * 100, 201), jdbc.queryForObject(
                        "SELECT COUNT(*) FROM expiry_notice WHERE warehouse_id='WH-BATCH'", Integer.class));
            }
            handler.expiryEligibilitySweep();
            assertEquals(201, jdbc.queryForObject("SELECT COUNT(*) FROM expiry_notice WHERE warehouse_id='WH-BATCH'", Integer.class));
        } finally { com.xxl.job.core.context.XxlJobContext.setXxlJobContext(null); }
    }

    @Test
    void sweepNoticesExpiredLotWithoutReleasingOrInventingExpiry() {
        Clock sweepClock = Clock.fixed(SWEEP_AT, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            ExpiryEligibilitySweep.Report first = new ExpiryEligibilitySweep(session, sweepClock)
                    .execute("ENT-1", "WH-A", "W-EXP");
            ExpiryEligibilitySweep.Report replay = new ExpiryEligibilitySweep(session, sweepClock)
                    .execute("ENT-1", "WH-A", "W-EXP");
            assertEquals(1, first.expiredLots());
            assertEquals(1, first.notices());
            assertEquals(1, first.openReservations());
            assertEquals(0, replay.notices());
            InventoryException expired = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, sweepClock).reserve("ENT-1", "WH-A", "OP-RSV-LATE",
                            "DOC", "ACTOR", "ALLOC-2", "ATT-2", "xid-2", 1L, "ReservationTccAction", 1L, DIGEST,
                            bucket("LOT-NEAR"), Quantity.parse("1", 0), "OL-2"));
            assertEquals("LOT_EXPIRED", expired.code());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM expiry_notice WHERE lot_id='LOT-NEAR' AND window_id='W-EXP'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT open_reservations FROM expiry_notice WHERE lot_id='LOT-NEAR' AND window_id='W-EXP'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM expiry_notice WHERE lot_id='LOT-FAR'", Integer.class));
        assertEquals(ReservationState.TRIED, jdbc.queryForObject(
                "SELECT state FROM reservation WHERE allocation_id='ALLOC-1'", String.class));
        assertEquals("2026-09-12T10:00:00Z", jdbc.queryForObject(
                "SELECT expires_at FROM lot WHERE id='LOT-NEAR'", java.sql.Timestamp.class).toInstant().toString());
        assertEquals("2026-10-01T10:00:00Z", jdbc.queryForObject(
                "SELECT expires_at FROM lot WHERE id='LOT-FAR'", java.sql.Timestamp.class).toInstant().toString());
    }

    private static StockBucketKey bucket(String lotId) {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-EXP", lotId, InventoryCodes.QUALITY_GOOD);
    }
}
