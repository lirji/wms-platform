package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

/** S7-04：稳定 cutoff 识别真实差异；缺水位不判丢失；审批不改写余额。 */
class StockInternalReconcileIT {
    private static final Instant CUTOFF = Instant.parse("2026-09-12T08:00:00Z");
    private static final Instant POSTED = Instant.parse("2026-09-12T07:00:00Z");
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
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("recon", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(ReconciliationMapper.class);
        config.addMapper(com.lrj.wms.inventory.archive.ArchivePlanMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(POSTED, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
            masterdata.createSku(SkuPolicy.create("SKU-Q", "ENT-1", "SKU-Q", "普通", "EA", 0, false, false, false, 1,
                    MasterdataCodes.STATE_ACTIVE), "UNIT-Q");
            masterdata.createSku(SkuPolicy.create("SKU-SN", "ENT-1", "SKU-SN", "序列号", "EA", 0, false, true, false, 1,
                    MasterdataCodes.STATE_ACTIVE), "UNIT-SN");
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-Q", "DOC", "ACTOR", bucket("SKU-Q"), Quantity.parse("5", 0));
            inventory.reserve("ENT-1", "WH-A", "OP-RSV", "DOC", "ACTOR", "ALLOC-1", "ATT-1", "xid-1", 1L,
                    "ReservationTccAction", 1L, DIGEST, bucket("SKU-Q"), Quantity.parse("1", 0), "OL-1");
            inventory.receive("ENT-1", "WH-A", "OP-SN", "DOC", "ACTOR", bucket("SKU-SN"), Quantity.parse("2", 0));
            session.commit();
        }
        String qtyBalance = jdbc.queryForObject("SELECT id FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-Q'", String.class);
        String snBalance = jdbc.queryForObject("SELECT id FROM stock_balance WHERE sku_id='SKU-SN'", String.class);
        jdbc.update("UPDATE stock_balance SET on_hand_qty=on_hand_qty+1 WHERE id=?", qtyBalance);
        jdbc.update("UPDATE stock_balance SET reserved_qty=2 WHERE id=?", qtyBalance);
        jdbc.update("INSERT INTO local_serial (id, enterprise_id, warehouse_id, serial_id, sku_id, lot_id, balance_id, "
                + "state, owner_epoch, receipt_operation_id, registry_state, version, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,0,?,?,0,?,?)",
                "LS-1", "ENT-1", "WH-A", "SN-1", "SKU-SN", MasterdataCodes.NO_LOT, snBalance, "AUTHORIZED", "OP-SN",
                "NONE", Timestamp.from(POSTED), Timestamp.from(POSTED));
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void stableCutoffFindsIsolatedGapsAndApproveDoesNotRewrite() {
        Clock clock = Clock.fixed(CUTOFF, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            StockInternalReconcile recon = new StockInternalReconcile(session, clock);
            Timestamp closed = Timestamp.from(CUTOFF);
            recon.closeWindow("ENT-1", "WH-A", "C-INT", closed, "SRC-1", "POST-1", "RCV-1");
            List<Map<String, Object>> before = recon.listCases("ENT-1", "WH-A", "C-INT");
            assertTrue(before.isEmpty());
            StockInternalReconcile.Report report = recon.execute("ENT-1", "WH-A", "C-INT");
            assertEquals(100, report.budget());
            assertTrue(report.opened() >= 3);
            List<Map<String, Object>> cases = recon.listCases("ENT-1", "WH-A", "C-INT");
            assertTrue(cases.stream().anyMatch(row -> StockInternalReconcile.BALANCE_LEDGER.equals(row.get("case_type"))));
            assertTrue(cases.stream().anyMatch(row -> StockInternalReconcile.RESERVED_MISMATCH.equals(row.get("case_type"))));
            assertTrue(cases.stream().anyMatch(row -> StockInternalReconcile.SERIAL_QTY.equals(row.get("case_type"))));
            Map<String, Object> ledgerCase = cases.stream()
                    .filter(row -> StockInternalReconcile.BALANCE_LEDGER.equals(row.get("case_type"))).findFirst()
                    .orElseThrow();
            BigDecimal beforeQty = jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-Q'",
                    BigDecimal.class);
            Map<String, Object> approved = recon.remediate("ENT-1", "WH-A", String.valueOf(ledgerCase.get("id")),
                    "APPROVE", "keep evidence", 0L, "auditor");
            assertEquals("REMEDIATING", approved.get("state"));
            assertEquals(Boolean.FALSE, approved.get("rewroteBalance"));
            assertEquals(0, beforeQty.compareTo(jdbc.queryForObject(
                    "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-Q'", BigDecimal.class)));
            executeSql(session, "UPDATE stock_balance SET on_hand_qty=on_hand_qty-1 WHERE warehouse_id='WH-A' AND sku_id='SKU-Q'");
            session.clearCache();
            StockInternalReconcile.Report again = recon.execute("ENT-1", "WH-A", "C-INT");
            assertEquals(1, again.closed());
            Map<String, Object> closedCase = recon.listCases("ENT-1", "WH-A", "C-INT").stream()
                    .filter(row -> StockInternalReconcile.BALANCE_LEDGER.equals(row.get("case_type"))).findFirst()
                    .orElseThrow();
            assertEquals("CLOSED", closedCase.get("state"));
            session.rollback();
        }
    }

    @Test
    void incompleteWatermarkIsNotMissingAndGraceKeepsLateArrival() {
        Clock clock = Clock.fixed(CUTOFF, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            StockInternalReconcile recon = new StockInternalReconcile(session, clock);
            Timestamp closed = Timestamp.from(CUTOFF);
            recon.closeWindow("ENT-1", "WH-A", "C-WM", closed, null, "POST-1", "RCV-1");
            recon.ingestSourceFact("ENT-1", "WH-A", "wms-inbound", "CMD-OLD", "EFF-OLD", "PHYSICAL",
                    new BigDecimal("1"), Timestamp.from(POSTED), "SRC-MISSING");
            recon.execute("ENT-1", "WH-A", "C-WM");
            List<Map<String, Object>> incomplete = recon.listCases("ENT-1", "WH-A", "C-WM");
            assertTrue(incomplete.stream().anyMatch(row -> StockInternalReconcile.SOURCE_INCOMPLETE
                    .equals(String.valueOf(row.get("discrepancy_code")))));
            assertTrue(incomplete.stream().noneMatch(row -> StockInternalReconcile.MISSING_RIGHT
                    .equals(String.valueOf(row.get("discrepancy_code")))));
            recon.closeWindow("ENT-1", "WH-A", "C-FULL", closed, "SRC-1", "POST-1", "RCV-1");
            recon.ingestSourceFact("ENT-1", "WH-A", "wms-inbound", "CMD-LATE", "EFF-LATE", "PHYSICAL",
                    new BigDecimal("1"), Timestamp.from(Instant.parse("2026-09-12T07:50:00Z")), "SRC-1");
            recon.ingestSourceFact("ENT-1", "WH-A", "wms-inbound", "CMD-OLD2", "EFF-OLD2", "PHYSICAL",
                    new BigDecimal("1"), Timestamp.from(POSTED), "SRC-1");
            executeSql(session, "INSERT INTO stock_posting (id, enterprise_id, warehouse_id, source_service, command_id, "
                    + "business_effect_key, action, execution_attempt_id, posting_type, quantity, source_execution_id, "
                    + "source_document_id, ledger_manifest, result_version, reversed_qty, version, created_at, updated_at) "
                    + "VALUES ('P-ORPHAN','ENT-1','WH-A','wms-outbound','CMD-ORPHAN','EFF-ORPHAN','SHIP','ATT-1','SHIP',"
                    + "1,'NONE','DOC','{}',0,0,0,'2026-09-12 07:00:00','2026-09-12 07:00:00')");
            session.clearCache();
            recon.execute("ENT-1", "WH-A", "C-FULL");
            List<Map<String, Object>> full = recon.listCases("ENT-1", "WH-A", "C-FULL");
            assertTrue(full.stream().anyMatch(row -> StockInternalReconcile.LATE_ARRIVAL
                    .equals(String.valueOf(row.get("discrepancy_code")))));
            assertTrue(full.stream().anyMatch(row -> StockInternalReconcile.MISSING_RIGHT
                    .equals(String.valueOf(row.get("discrepancy_code")))));
            assertTrue(full.stream().anyMatch(row -> StockInternalReconcile.MISSING_LEFT
                    .equals(String.valueOf(row.get("discrepancy_code")))));
            session.rollback();
        }
    }

    @Test
    void checkpointsSurviveRestartAndNeverCloseUnvisitedCases() {
        Clock clock = Clock.fixed(CUTOFF, ZoneOffset.UTC);
        Timestamp now = Timestamp.from(POSTED);
        for (int i = 0; i < 205; i++) {
            jdbc.update("INSERT INTO stock_balance (id,enterprise_id,warehouse_id,owner_id,location_id,sku_id,lot_id,quality_code,on_hand_qty,reserved_qty,free_execution_claim_qty,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,0,0,0,?,?)",
                    "BP-%03d".formatted(i), "ENT-1", "WH-P", "OWNER-" + i, "LOC-P", "SKU-Q", "NO_LOT", "GOOD", i == 0 ? 1 : 0, now, now);
        }
        try (var session = sessions.openSession(false)) {
            new StockInternalReconcile(session, clock).closeWindow("ENT-1", "WH-P", "C-PAGE", Timestamp.from(CUTOFF), "S", "P", "R");
            var mapper = session.getMapper(ReconciliationMapper.class);
            mapper.insertCaseIgnore("CASE-LAST", "ENT-1", "WH-P", "C-PAGE", "BALANCE_LEDGER", "QTY_MISMATCH", "BP-204", "SKU-Q", BigDecimal.ZERO, BigDecimal.ONE, "fixture", now);
            mapper.casCase("ENT-1", "WH-P", "CASE-LAST", "OPEN", "REMEDIATING", 0, "auditor", "repair-op", now);
            session.commit();
        }
        jdbc.execute("ALTER TABLE reconciliation_scan ADD CONSTRAINT test_checkpoint_failure CHECK (cutoff_id <> 'C-PAGE' OR version=0)");
        try (var session = sessions.openSession(false)) {
            assertThrows(RuntimeException.class, () -> new StockInternalReconcile(session, clock).execute("ENT-1", "WH-P", "C-PAGE"));
            session.rollback();
        } finally { jdbc.execute("ALTER TABLE reconciliation_scan DROP CHECK test_checkpoint_failure"); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_case WHERE warehouse_id='WH-P'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_scan WHERE warehouse_id='WH-P'", Integer.class));
        for (int page = 0; page < 3; page++) {
            try (var session = sessions.openSession(false)) {
                var result = new StockInternalReconcile(session, clock).execute("ENT-1", "WH-P", "C-PAGE");
                assertEquals(page < 2 ? 100 : 5, result.scanned());
                assertEquals(page == 2, result.cycleCompleted());
                assertEquals(page == 2 ? 1 : 0, result.closed());
                session.commit();
            }
            assertEquals(page < 2 ? "REMEDIATING" : "CLOSED", jdbc.queryForObject("SELECT state FROM reconciliation_case WHERE id='CASE-LAST'", String.class));
        }
        assertEquals("repair-op", jdbc.queryForObject("SELECT remediation_operation_id FROM reconciliation_case WHERE id='CASE-LAST'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT completed_cycles FROM reconciliation_scan WHERE warehouse_id='WH-P'", Integer.class));
        try (var session = sessions.openSession(false)) {
            assertThrows(com.lrj.wms.inventory.jobs.JobRunException.class, () -> new StockInternalReconcile(session, clock)
                    .closeWindow("ENT-1", "WH-P", "C-PAGE", Timestamp.from(CUTOFF.plusSeconds(1)), "S", "P", "R"));
            session.rollback();
        }
    }

    @Test
    void archivePlansOnlyWithExplicitPolicyAndAtomicResume() {
        var archiveBucket = StockBucketKey.of("ENT-1", "WH-ARCH", "OWNER-1", "LOC-ARCH", "SKU-Q", "NO_LOT", "GOOD");
        try (var session = sessions.openSession(false)) {
            var md = new MasterdataService(session, Clock.fixed(POSTED,ZoneOffset.UTC));
            md.createWarehouse("WH-ARCH","ENT-1","ARCH","归档验证仓","Asia/Shanghai");
            md.createLocation("LOC-ARCH","GATE-ARCH","ENT-1","WH-ARCH","ARCH","A","STORAGE", new BigDecimal("1000"), "EA");
            var inventory = new InventoryApplicationService(session, Clock.fixed(POSTED,ZoneOffset.UTC));
            for (int i=0;i<205;i++) inventory.receive("ENT-1","WH-ARCH","AR-"+i,"DOC","ACTOR",archiveBucket,Quantity.parse("1",0));
            new InventoryApplicationService(session,Clock.fixed(CUTOFF.plusSeconds(30),ZoneOffset.UTC))
                    .receive("ENT-1","WH-ARCH","AR-RECENT","DOC","ACTOR",archiveBucket,Quantity.parse("1",0));
            session.commit();
        }
        Clock clock = Clock.fixed(CUTOFF.plusSeconds(60),ZoneOffset.UTC);
        jdbc.execute("ALTER TABLE archive_plan ADD CONSTRAINT test_archive_failure CHECK (run_key <> 'A-RUN' OR version=0)");
        try (var session = sessions.openSession(false)) {
            assertThrows(RuntimeException.class, () -> new com.lrj.wms.inventory.archive.ArchivePlanner(session,clock)
                    .execute("ENT-1","WH-ARCH","A-RUN",CUTOFF,"POLICY-REF","operator"));
            session.rollback();
        } finally { jdbc.execute("ALTER TABLE archive_plan DROP CHECK test_archive_failure"); }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM archive_plan_item WHERE warehouse_id='WH-ARCH'",Integer.class));
        String manifest = null;
        for(int page=0;page<3;page++) {
            try(var session=sessions.openSession(false)) {
                var result=new com.lrj.wms.inventory.archive.ArchivePlanner(session,clock).execute("ENT-1","WH-ARCH","A-RUN",CUTOFF,"POLICY-REF","operator");
                assertEquals(page==0?"PLANNING":"PLANNED_EXPORT",result.get("state"));
                assertEquals(page==0?200:205,((Number)result.get("candidateCount")).intValue());
                assertEquals(false,result.get("deleted")); assertEquals(false,result.get("exported"));
                if(page==2) assertEquals(manifest,result.get("manifestHash"));
                manifest=String.valueOf(result.get("manifestHash"));
                session.commit();
            }
        }
        try(var session=sessions.openSession(false)) {
            assertThrows(com.lrj.wms.inventory.jobs.JobRunException.class, () -> new com.lrj.wms.inventory.archive.ArchivePlanner(session,clock)
                    .execute("ENT-1","WH-ARCH","A-RUN",CUTOFF,"DIFFERENT-POLICY","operator"));
            session.rollback();
        }
        assertEquals(205,jdbc.queryForObject("SELECT COUNT(*) FROM archive_plan_item WHERE warehouse_id='WH-ARCH'",Integer.class));
        assertEquals(206,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE warehouse_id='WH-ARCH'",Integer.class));
        assertEquals(0,new BigDecimal("206").compareTo(jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-ARCH'",BigDecimal.class)));
    }

    private static StockBucketKey bucket(String skuId) {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", skuId, MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static void executeSql(SqlSession session, String sql) {
        try {
            session.getConnection().createStatement().executeUpdate(sql);
        } catch (SQLException error) {
            throw new IllegalStateException(sql, error);
        }
    }
}
