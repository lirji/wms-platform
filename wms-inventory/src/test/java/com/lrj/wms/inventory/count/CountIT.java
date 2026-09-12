package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** S6-03：排空、冻结快照、点数复盘、审批调整与预占冲突。不是 AC-18/19 生产。 */
class CountIT {
    private static final Instant NOW = Instant.parse("2026-09-12T03:00:00Z");
    private static final String DIGEST = "d".repeat(64);
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
        config.addMapper(CountMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-2", "GATE-2", "ENT-1", "WH-A", "A-02", "A", "STORAGE", new BigDecimal("100"),
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
    void actualRecoveryHandlerCommitsAdjustmentAndOutboxAfterRollback() {
        Clock clock = Clock.systemUTC();
        String plan = "CP-HANDLER";
        String lineId;
        try (var session = sessions.openSession(false)) {
            new MasterdataService(session, clock).createLocation("LOC-HANDLER", "GATE-HANDLER", "ENT-1", "WH-A",
                    "HANDLER", "A", "STORAGE", new BigDecimal("100"), "EA");
            var bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-HANDLER", "SKU-HANDLER",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "HANDLER-RCV", plan,
                    "RECEIVER", bucket, Quantity.parse("10", 0));
            var counts = new CountService(session, clock);
            counts.create("ENT-1", "WH-A", plan, "CYCLE", List.of("LOC-HANDLER"));
            counts.startQuiescing("ENT-1", "WH-A", plan);
            counts.freeze("ENT-1", "WH-A", plan);
            lineId = String.valueOf(session.getMapper(CountMapper.class).listLines("ENT-1", "WH-A", plan).getFirst().get("id"));
            counts.observe("ENT-1", "WH-A", plan, lineId, "OBS-HANDLER", "7", "COUNTER", 1);
            counts.submitReview("ENT-1", "WH-A", plan);
            counts.approve("ENT-1", "WH-A", plan, "APP-HANDLER", "APPROVER");
            session.commit();
        }
        try (var session = sessions.openSession(false)) {
            new CountService(session, clock).applyLine("ENT-1", "WH-A", plan, lineId, "HANDLER-ABORTED", "ACTOR");
            session.rollback();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='HANDLER-ABORTED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='HANDLER-ABORTED'", Integer.class));
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("sqlSessionFactory", sessions);
        var handler = new com.lrj.wms.inventory.jobs.InventoryCatalogJobs(
                beans.getBeanProvider(com.lrj.wms.inventory.tcc.TccReservationWatch.class), beans.getBeanProvider(SqlSessionFactory.class));
        com.xxl.job.core.context.XxlJobContext.setXxlJobContext(new com.xxl.job.core.context.XxlJobContext(
                1, "ENT-1,WH-A," + plan, 1, System.currentTimeMillis(), "", 0, 1));
        try { handler.countApplyRecovery(); handler.countApplyRecovery(); }
        finally { com.xxl.job.core.context.XxlJobContext.setXxlJobContext(null); }
        assertEquals(0, new BigDecimal("7").compareTo(jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE location_id='LOC-HANDLER'", BigDecimal.class)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event o JOIN stock_ledger l ON l.operation_id=o.operation_id WHERE l.document_id=? AND l.reason_code='COUNT_ADJUST'", Integer.class, plan));
    }

    @Test
    void recoveryIsBoundedFencedAndContinuesAfterReservationConflict() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String plan = "CP-RECOVERY";
        try (SqlSession session = sessions.openSession(false)) {
            var masterdata = new MasterdataService(session, clock);
            masterdata.createLocation("LOC-REC", "GATE-REC", "ENT-1", "WH-A", "REC", "A", "STORAGE",
                    new BigDecimal("1000"), "EA");
            var inventory = new InventoryApplicationService(session, clock);
            for (int i = 0; i < 23; i++) {
                var bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-REC", "SKU-REC-" + i,
                        MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
                inventory.receive("ENT-1", "WH-A", "REC-RCV-" + i, plan, "ACTOR", bucket, Quantity.parse("10", 0));
                if (i == 0) inventory.reserve("ENT-1", "WH-A", "REC-RSV", plan, "ACTOR", "REC-ALLOC", "REC-ATT",
                        "rec-xid", 77L, "ReservationTccAction", 1L, DIGEST, bucket, Quantity.parse("6", 0), "REC-LINE");
            }
            var counts = new CountService(session, clock);
            counts.create("ENT-1", "WH-A", plan, "CYCLE", List.of("LOC-REC"));
            counts.startQuiescing("ENT-1", "WH-A", plan);
            counts.freeze("ENT-1", "WH-A", plan);
            session.commit();
        }
        // 冲突行排在首位，用真实预占完整性证明坏行不会饿死后续行。
        jdbc.update("UPDATE count_line l JOIN stock_balance b ON b.id=l.balance_id SET l.id='000-REC-CONFLICT' WHERE l.count_plan_id=? AND b.sku_id='SKU-REC-0'", plan);
        try (SqlSession session = sessions.openSession(false)) {
            var counts = new CountService(session, clock);
            for (var line : session.getMapper(CountMapper.class).listLines("ENT-1", "WH-A", plan)) {
                String id = String.valueOf(line.get("id"));
                counts.observe("ENT-1", "WH-A", plan, id, "OBS-REC-" + id, "3", "COUNTER", 1);
            }
            counts.submitReview("ENT-1", "WH-A", plan);
            counts.approve("ENT-1", "WH-A", plan, "APP-REC", "APPROVER");
            session.commit();
        }
        var recovery = new CountApplyRecovery(sessions, clock);
        assertEquals(new CountApplyRecovery.Report(19, 1), recovery.execute("ENT-1", "WH-A", plan));
        assertEquals(new CountApplyRecovery.Report(3, 0), recovery.execute("ENT-1", "WH-A", plan));
        assertEquals(new CountApplyRecovery.Report(0, 0), recovery.execute("ENT-1", "WH-A", plan));
        assertEquals(22, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE document_id=? AND reason_code='COUNT_ADJUST'", Integer.class, plan));
        assertEquals(22, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event o JOIN stock_ledger l ON l.operation_id=o.operation_id WHERE l.document_id=? AND l.reason_code='COUNT_ADJUST'", Integer.class, plan));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-REC'", String.class));
        assertEquals("RESERVATION_CONFLICT", jdbc.queryForObject("SELECT recovery_error_code FROM count_line WHERE id='000-REC-CONFLICT'", String.class));
        assertEquals("job:countApplyRecovery", jdbc.queryForObject("SELECT actor_id FROM stock_ledger WHERE document_id=? AND reason_code='COUNT_ADJUST' LIMIT 1", String.class, plan));
        // 新代际领取模拟崩溃接管，旧执行器的失败回写不能覆盖新的租约。
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(CountMapper.class);
            mapper.lockPlan("ENT-1", "WH-A", plan);
            assertEquals(1, mapper.claimRecovery("ENT-1", "WH-A", "000-REC-CONFLICT", 1,
                    java.sql.Timestamp.from(NOW.plusSeconds(30))));
            assertEquals(0, mapper.failRecovery("ENT-1", "WH-A", "000-REC-CONFLICT", 1,
                    java.sql.Timestamp.from(NOW), "OLD_WORKER"));
            session.commit();
        }
        for (int i = 1; i <= 6; i++) {
            var restarted = new CountApplyRecovery(sessions, Clock.fixed(NOW.plusSeconds(i * 120L), ZoneOffset.UTC));
            assertEquals(new CountApplyRecovery.Report(0, 1), restarted.execute("ENT-1", "WH-A", plan));
        }
        assertEquals(new CountApplyRecovery.Report(0, 0), new CountApplyRecovery(sessions,
                Clock.fixed(NOW.plusSeconds(5000), ZoneOffset.UTC)).execute("ENT-1", "WH-A", plan));
        assertEquals(8, jdbc.queryForObject("SELECT recovery_attempts FROM count_line WHERE id='000-REC-CONFLICT'", Integer.class));
        assertEquals(22, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE document_id=? AND reason_code='COUNT_ADJUST'", Integer.class, plan));
    }

    @Test
    void quiesceFreezeObserveApproveAndReservationConflict() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey good = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-C", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            CountService counts = new CountService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-C-RCV", "DOC-C", "ACTOR", good, Quantity.parse("10", 0));
            assertEquals(CountService.DRAFT,
                    counts.create("ENT-1", "WH-A", "CP-1", "CYCLE", List.of("LOC-1")).get("status"));
            assertEquals(CountService.QUIESCING, counts.startQuiescing("ENT-1", "WH-A", "CP-1").get("status"));
            InventoryException denied = assertThrows(InventoryException.class,
                    () -> inventory.reserve("ENT-1", "WH-A", "OP-C-RSV", "DOC-C", "ACTOR", "ALLOC-C", "ATT-C", "xid-c",
                            1L, "ReservationTccAction", 1L, DIGEST, good, Quantity.parse("1", 0), "OL-C"));
            assertEquals("STOCK_FROZEN", denied.code());
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-1");
            assertEquals(CountService.FROZEN, frozen.get("status"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            assertEquals(1, lines.size());
            String lineId = String.valueOf(lines.getFirst().get("id"));
            assertEquals(0, new BigDecimal(String.valueOf(lines.getFirst().get("snapshotQty")))
                    .compareTo(new BigDecimal("10.000000")));
            assertEquals("8.000000", counts.observe("ENT-1", "WH-A", "CP-1", lineId, "OBS-1", "8", "ACTOR", 1)
                    .get("qty").toString());
            assertEquals("7.000000", counts.observe("ENT-1", "WH-A", "CP-1", lineId, "OBS-2", "7", "ACTOR", 2)
                    .get("qty").toString());
            assertEquals("8.000000", counts.observe("ENT-1", "WH-A", "CP-1", lineId, "OBS-1", "8", "ACTOR", 1)
                    .get("qty").toString());
            assertEquals(2, session.getMapper(CountMapper.class).countObservations("ENT-1", "WH-A", lineId));
            assertEquals(CountService.REVIEWING, counts.submitReview("ENT-1", "WH-A", "CP-1").get("status"));
            assertEquals(CountService.APPROVED, counts.approve("ENT-1", "WH-A", "CP-1", "AP-1", "APPR").get("status"));
            assertEquals(CountService.LINE_APPLIED,
                    counts.applyLine("ENT-1", "WH-A", "CP-1", lineId, "OP-C-ADJ", "ACTOR").get("status"));
            assertEquals(CountService.COMPLETED, counts.unfreeze("ENT-1", "WH-A", "CP-1").get("status"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-C'",
                BigDecimal.class).compareTo(new BigDecimal("7.000000")));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-1'",
                String.class));
        assertNull(jdbc.queryForObject("SELECT count_plan_id FROM location_gate WHERE location_id='LOC-1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-C-ADJ'",
                Integer.class));

        StockBucketKey conflict = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-CF", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            CountService counts = new CountService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-CF-RCV", "DOC-CF", "ACTOR", conflict, Quantity.parse("10", 0));
            inventory.reserve("ENT-1", "WH-A", "OP-CF-RSV", "DOC-CF", "ACTOR", "ALLOC-CF", "ATT-CF", "xid-cf", 2L,
                    "ReservationTccAction", 1L, DIGEST, conflict, Quantity.parse("6", 0), "OL-CF");
            counts.create("ENT-1", "WH-A", "CP-2", "CYCLE", List.of("LOC-1"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-2");
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-2");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            String conflictLine = null;
            int obs = 1;
            InventoryMapper balances = session.getMapper(InventoryMapper.class);
            for (Map<String, Object> line : lines) {
                String sku = String.valueOf(balances.lockBalanceById("ENT-1", "WH-A", String.valueOf(line.get("balanceId")))
                        .get("sku_id"));
                String counted = "SKU-CF".equals(sku) ? "3" : "7";
                counts.observe("ENT-1", "WH-A", "CP-2", String.valueOf(line.get("id")), "OBS-CF-" + obs, counted, "ACTOR",
                        1);
                if ("SKU-CF".equals(sku)) {
                    conflictLine = String.valueOf(line.get("id"));
                }
                obs++;
            }
            assertNotNull(conflictLine);
            String applyConflictLine = conflictLine;
            counts.submitReview("ENT-1", "WH-A", "CP-2");
            counts.approve("ENT-1", "WH-A", "CP-2", "AP-2", "APPR");
            InventoryException conflicted = assertThrows(InventoryException.class,
                    () -> counts.applyLine("ENT-1", "WH-A", "CP-2", applyConflictLine, "OP-CF-ADJ", "ACTOR"));
            assertEquals("RESERVATION_CONFLICT", conflicted.code());
            InventoryException pending = assertThrows(InventoryException.class,
                    () -> counts.unfreeze("ENT-1", "WH-A", "CP-2"));
            assertEquals("COUNT_APPLY_PENDING", pending.code());
            session.commit();
        }
        assertEquals("RESERVATION_CONFLICT", jdbc.queryForObject(
                "SELECT status FROM count_line WHERE count_plan_id='CP-2' AND balance_id="
                        + "(SELECT id FROM stock_balance WHERE sku_id='SKU-CF')", String.class));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-1'",
                String.class));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-CF'",
                BigDecimal.class).compareTo(new BigDecimal("10.000000")));

        StockBucketKey drain = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-2", "SKU-DR", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            CountService counts = new CountService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-DR-RCV", "DOC-DR", "ACTOR", drain, Quantity.parse("5", 0));
            session.commit();
        }
        jdbc.update("UPDATE stock_balance SET free_execution_claim_qty=1 WHERE sku_id='SKU-DR'");
        try (SqlSession session = sessions.openSession(false)) {
            CountService counts = new CountService(session, clock);
            counts.create("ENT-1", "WH-A", "CP-3", "CYCLE", List.of("LOC-2"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-3");
            InventoryException draining = assertThrows(InventoryException.class,
                    () -> counts.freeze("ENT-1", "WH-A", "CP-3"));
            assertEquals("COUNT_DRAIN_PENDING", draining.code());
            session.commit();
        }
        assertEquals("QUIESCING", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-2'",
                String.class));
    }
}
