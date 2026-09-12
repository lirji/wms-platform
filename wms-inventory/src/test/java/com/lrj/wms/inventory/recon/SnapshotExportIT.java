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
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        Configuration config = new Configuration(new Environment("snapshot", new JdbcTransactionFactory(), source));
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
    void exportIsIdempotentAndQuantityIsNotMoney() {
        Clock clock = Clock.fixed(CUTOFF, ZoneOffset.UTC);
        Timestamp closed = Timestamp.from(CUTOFF);
        try (SqlSession session = sessions.openSession(false)) {
            SnapshotExportService export = new SnapshotExportService(session, clock);
            JobRunException incomplete = assertThrows(JobRunException.class,
                    () -> export.export("ENT-1", "WH-A", "C-SNAP", closed, null, "POST-1", "RCV-1"));
            assertEquals("SOURCE_INCOMPLETE", incomplete.code());
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
            session.rollback();
        }
    }
}
