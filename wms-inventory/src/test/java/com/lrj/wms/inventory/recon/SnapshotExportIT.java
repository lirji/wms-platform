package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
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
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S8-01：数量事实快照与不可变 manifest，不把数量伪装成金额。 */
class SnapshotExportIT {
    private static final Instant CUTOFF = Instant.parse("2026-09-12T10:00:00Z");
    private static final Instant POSTED = Instant.parse("2026-09-12T09:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;

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
        Configuration config = new Configuration(new Environment("snapshot", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(SnapshotMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(POSTED, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
            masterdata.createSku(SkuPolicy.create("SKU-Q", "ENT-1", "SKU-Q", "普通", "EA", 0, false, false, false, 1,
                    MasterdataCodes.STATE_ACTIVE), "UNIT-Q");
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-SNAP", "DOC", "ACTOR",
                    StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-Q", MasterdataCodes.NO_LOT,
                            InventoryCodes.QUALITY_GOOD),
                    Quantity.parse("4", 0));
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
    void resumesAcrossTransactionsAndExportsEveryHistoricalBalance() {
        Clock before = Clock.fixed(POSTED, ZoneOffset.UTC);
        Clock after = Clock.fixed(CUTOFF.plusSeconds(60), ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, before);
            masterdata.createWarehouse("WH-PAGES", "ENT-1", "PAGES", "分页快照仓", "UTC");
            masterdata.createLocation("LOC-PAGES", "GATE-PAGES", "ENT-1", "WH-PAGES", "P-01", "P", "STORAGE",
                    new BigDecimal("1000"), "EA");
            for (int i = 0; i < 251; i++) {
                var bucket = StockBucketKey.of("ENT-1", "WH-PAGES", "OWNER-" + i, "LOC-PAGES", "SKU-Q",
                        MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
                new InventoryApplicationService(session, before).receive("ENT-1", "WH-PAGES", "BEFORE-" + i,
                        "DOC", "ACTOR", bucket, Quantity.parse("4", 0));
                // 截止之后同一桶继续收货，历史快照仍必须导出4，而不是丢桶或导出9。
                new InventoryApplicationService(session, after).receive("ENT-1", "WH-PAGES", "AFTER-" + i,
                        "DOC", "ACTOR", bucket, Quantity.parse("5", 0));
            }
            session.commit();
        }
        try(var session=sessions.openSession(false)) {
            VerifiedWindowFixture.seed(session,"ENT-1","WH-PAGES","C-PAGES",Timestamp.from(CUTOFF),"SRC","POST","RCV");session.commit();
        }
        String snapshotId = null;
        for (int part = 1; part <= 3; part++) {
            try (SqlSession session = sessions.openSession(false)) {
                var result = new SnapshotExportService(session, after).export("ENT-1", "WH-PAGES", "C-PAGES",
                        Timestamp.from(CUTOFF), "SRC", "POST", "RCV");
                snapshotId = String.valueOf(result.get("snapshotId"));
                assertEquals(part < 3 ? "EXPORTING" : "COMPLETE", result.get("state"));
                assertEquals(Math.min(part * 100, 251), ((Number) result.get("rowCount")).intValue());
                session.commit();
            }
        }
        int cursor = 0;
        int rows = 0;
        try (SqlSession session = sessions.openSession(false)) {
            var service = new SnapshotExportService(session, after);
            do {
                var result = service.get("ENT-1", "WH-PAGES", snapshotId, cursor);
                @SuppressWarnings("unchecked")
                var parts = (List<Map<String, Object>>) result.get("parts");
                assertEquals(1, parts.size());
                for (String line : String.valueOf(parts.getFirst().get("payload")).split("\n")) {
                    assertTrue(line.contains("\"quantity\":\"4\""), line);
                    rows++;
                }
                cursor = result.get("nextPartNo") == null ? 0 : ((Number) result.get("nextPartNo")).intValue();
            } while (cursor != 0);
            assertEquals(251, rows);
            assertThrows(JobRunException.class, () -> service.export("ENT-1", "WH-PAGES", "C-PAGES",
                    Timestamp.from(CUTOFF), "CHANGED", "POST", "RCV"));
        }
    }

    @Test
    void exportIsIdempotentAndQuantityIsNotMoney() {
        Clock clock = Clock.fixed(CUTOFF, ZoneOffset.UTC);
        Timestamp closed = Timestamp.from(CUTOFF);
        try (SqlSession session = sessions.openSession(false)) {
            SnapshotExportService export = new SnapshotExportService(session, clock);
            JobRunException incomplete = assertThrows(JobRunException.class,
                    () -> export.export("ENT-1", "WH-A", "C-SNAP", closed, null, "POST-1", "RCV-1"));
            assertEquals("SOURCE_INCOMPLETE", incomplete.code());
            assertEquals("SOURCE_INCOMPLETE",assertThrows(JobRunException.class,() -> export.export("ENT-1","WH-A","C-SNAP",closed,"SRC-1","POST-1","RCV-1")).code());
            VerifiedWindowFixture.seed(session,"ENT-1","WH-A","C-SNAP",closed,"SRC-1","POST-1","RCV-1");
            Map<String, Object> first = export.export("ENT-1", "WH-A", "C-SNAP", closed, "SRC-1", "POST-1", "RCV-1");
            Map<String, Object> replay = export.export("ENT-1", "WH-A", "C-SNAP", closed, "SRC-1", "POST-1", "RCV-1");
            assertEquals(first.get("snapshotId"), replay.get("snapshotId"));
            assertEquals("COMPLETE", first.get("state"));
            assertEquals(String.valueOf(first.get("manifest")), String.valueOf(replay.get("manifest")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) first.get("parts");
            assertEquals(1, parts.size());
            String payload = String.valueOf(parts.getFirst().get("payload"));
            assertTrue(payload.contains("\"quantity\":\"4\""));
            assertTrue(payload.contains("\"unit\":\"EA\""));
            assertFalse(payload.contains("currency"));
            assertFalse(payload.contains("amountMinor"));
            assertEquals(WarehouseQuantityFact.SCHEMA_VERSION, ((Number) first.get("schemaVersion")).intValue());
            // 模拟旧版本留下的complete位；后续读取也必须检查服务端证明版本。
            try(var statement=session.getConnection().prepareStatement("UPDATE reconciliation_cutoff SET evidence_version=0 WHERE enterprise_id='ENT-1' AND warehouse_id='WH-A' AND cutoff_id='C-SNAP'")) {
                statement.executeUpdate();session.clearCache();
            } catch(java.sql.SQLException failure) {throw new IllegalStateException(failure);}
            assertEquals("SOURCE_INCOMPLETE",assertThrows(JobRunException.class,() -> export.get("ENT-1","WH-A",String.valueOf(first.get("snapshotId")))).code());
            session.rollback();
        }
    }

    /** 预建空桶的首笔真实入库晚于截止时刻可排除，历史正库存缺流水则必须失败。 */
    @Test void excludesOnlyProvablyEmptyBucketBeforeFirstLateLedger() throws Exception {
        try(var session=sessions.openSession(false)) {
            var before=Clock.fixed(POSTED,ZoneOffset.UTC);var after=Clock.fixed(CUTOFF.plusSeconds(60),ZoneOffset.UTC);
            var masterdata=new MasterdataService(session,before);
            masterdata.createWarehouse("WH-FIRST","ENT-1","FIRST","晚入库仓","UTC");
            masterdata.createLocation("LOC-FIRST","GATE-FIRST","ENT-1","WH-FIRST","FIRST","FIRST","STORAGE",new BigDecimal("100"),"EA");
            var mapper=session.getMapper(InventoryMapper.class);
            mapper.insertBalance("B-FIRST","ENT-1","WH-FIRST","OWNER-1","LOC-FIRST","SKU-Q",MasterdataCodes.NO_LOT,InventoryCodes.QUALITY_GOOD,Timestamp.from(POSTED));
            new InventoryApplicationService(session,after).receive("ENT-1","WH-FIRST","FIRST-OP","DOC","ACTOR",
                    StockBucketKey.of("ENT-1","WH-FIRST","OWNER-1","LOC-FIRST","SKU-Q",MasterdataCodes.NO_LOT,InventoryCodes.QUALITY_GOOD),Quantity.parse("5",0));
            VerifiedWindowFixture.seed(session,"ENT-1","WH-FIRST","C-FIRST",Timestamp.from(CUTOFF),"SRC","POST","RCV");
            var exported=new SnapshotExportService(session,after).export("ENT-1","WH-FIRST","C-FIRST",Timestamp.from(CUTOFF),"SRC","POST","RCV");
            assertEquals("COMPLETE",exported.get("state"));assertEquals(0,((Number)exported.get("rowCount")).intValue());
            mapper.insertBalance("B-DIRTY","ENT-1","WH-FIRST","OWNER-DIRTY","LOC-FIRST","SKU-Q",MasterdataCodes.NO_LOT,InventoryCodes.QUALITY_GOOD,Timestamp.from(POSTED));
            try(var sql=session.getConnection().createStatement()) {sql.executeUpdate("UPDATE stock_balance SET on_hand_qty=7 WHERE id='B-DIRTY'");session.clearCache();}
            VerifiedWindowFixture.seed(session,"ENT-1","WH-FIRST","C-DIRTY",Timestamp.from(CUTOFF),"SRC","POST","RCV");
            assertEquals("SOURCE_INCOMPLETE",assertThrows(JobRunException.class,() -> new SnapshotExportService(session,after)
                    .export("ENT-1","WH-FIRST","C-DIRTY",Timestamp.from(CUTOFF),"SRC","POST","RCV")).code());
        }
    }
}
