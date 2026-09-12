package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.effect.domain.EffectCodes;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
import com.lrj.wms.inventory.inventory.domain.CommandDigest;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.domain.StockCommandCodes;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** S2-04a T2 收货过账、取消墓碑与恢复查询。不是并发 AC-03。 */
class StockCommandIT {
    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");

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
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(EffectMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(StockCommandMapper.class);
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
    void sameExternalKeyPostsIndependentlyInEachWarehouse() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            var masterdata = new MasterdataService(session, clock);
            for (String warehouse : java.util.List.of("WH-KEY-A", "WH-KEY-B")) {
                masterdata.createWarehouse(warehouse, "ENT-1", warehouse, warehouse, "Asia/Shanghai");
                masterdata.createLocation("LOC-" + warehouse, "GATE-" + warehouse, "ENT-1", warehouse,
                        "KEY-01", "A", "STORAGE", new BigDecimal("100"), "EA");
                var bucket = StockBucketKey.of("ENT-1", warehouse, "OWNER-1", "LOC-" + warehouse, "SKU-KEY",
                        MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
                var result = new StockCommandService(session, clock).applyReceive("ENT-1", warehouse,
                        StockCommandCodes.SOURCE_INBOUND, "SHARED-STOCK-KEY", "RECEIPT", "PART", "LINE", "DOC",
                        "ACTOR", "EXEC", bucket, Quantity.parse("3", 0));
                assertEquals("APPLIED", result.get("state"));
            }
            session.commit();
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_command WHERE command_id='SHARED-STOCK-KEY'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='SHARED-STOCK-KEY'", Integer.class));
    }

    @Test
    void applyReplayCancelTombstoneAndRejectLateApply() {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-CMD", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Quantity qty = Quantity.parse("3", 0);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> first = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RCV",
                    "RCPT-1", "PART-1", "LINE-1", "DOC-1", "ACTOR", "EXEC-1", bucket, qty);
            Map<String, Object> replay = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RCV",
                    "RCPT-1", "PART-1", "LINE-1", "DOC-1", "ACTOR", "EXEC-1", bucket, qty);
            assertEquals(StockCommandCodes.CMD_APPLIED, first.get("state"));
            assertEquals("CMD-RCV", replay.get("commandId"));
            Map<String, Object> stillApplied = service.cancel("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RCV",
                    EffectCodes.ACTION_RECEIVE, String.valueOf(first.get("businessEffectKey")), "x".repeat(64));
            assertEquals(StockCommandCodes.CMD_APPLIED, stillApplied.get("state"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-CMD'", BigDecimal.class)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='CMD-RCV'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='CMD-RCV'", Integer.class));

        String openEffect = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, "
                + "attempt_no, state, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL,0,'REGISTERED',0,"
                + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))", openEffect, "ENT-1", "WH-A",
                StockCommandCodes.SOURCE_INBOUND, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART, "RCPT-X",
                "PART-X", "LINE-X", openEffect);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            String digest = CommandDigest.v1(EffectCodes.ACTION_RECEIVE, "DOC-X", bucket, "2", "CMD-X");
            Map<String, Object> cancelled = service.cancel("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-X",
                    EffectCodes.ACTION_RECEIVE, openEffect, digest);
            assertEquals(StockCommandCodes.CMD_CANCELLED, cancelled.get("state"));
            assertEquals(StockCommandCodes.CMD_CANCELLED,
                    service.get("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-X").get("state"));
            Map<String, Object> late = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-X",
                    "RCPT-X", "PART-X", "LINE-X", "DOC-X", "ACTOR", "EXEC-X", bucket, Quantity.parse("2", 0));
            assertEquals(StockCommandCodes.CMD_CANCELLED, late.get("state"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='CMD-X'", Integer.class));
    }
}
