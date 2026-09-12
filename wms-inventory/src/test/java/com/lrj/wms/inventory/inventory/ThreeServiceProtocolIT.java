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
import java.nio.file.Path;
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

/**
 * 三个独立 MySQL：入库 T1 → 库存 T2 → 入库 T3；出库 T1 → 库存取消墓碑 → 出库 T3。
 * 不经 Kafka，HTTP 触发等价于 T1 提交后调用。
 */
class ThreeServiceProtocolIT {
    private static final Instant NOW = Instant.parse("2026-09-11T04:00:00Z");

    private static MySQLContainer inboundDb;
    private static MySQLContainer inventoryDb;
    private static MySQLContainer outboundDb;
    private static JdbcTemplate inboundJdbc;
    private static JdbcTemplate inventoryJdbc;
    private static JdbcTemplate outboundJdbc;
    private static SqlSessionFactory inventorySessions;

    @BeforeAll
    static void prepare() {
        inboundDb = start("wms_inbound");
        inventoryDb = start("wms_inventory");
        outboundDb = start("wms_outbound");
        inboundJdbc = jdbc(inboundDb);
        inventoryJdbc = jdbc(inventoryDb);
        outboundJdbc = jdbc(outboundDb);
        Path root = Path.of("").toAbsolutePath();
        if (root.endsWith("wms-inventory")) {
            root = root.getParent();
        }
        Flyway.configure().dataSource(dataSource(inboundDb))
                .locations("filesystem:" + root.resolve("wms-inbound/src/main/resources/db/migration")).load().migrate();
        Flyway.configure().dataSource(dataSource(inventoryDb)).locations("classpath:db/migration").load().migrate();
        Flyway.configure().dataSource(dataSource(outboundDb))
                .locations("filesystem:" + root.resolve("wms-outbound/src/main/resources/db/migration")).load().migrate();
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(),
                dataSource(inventoryDb)));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(EffectMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(StockCommandMapper.class);
        inventorySessions = new SqlSessionFactoryBuilder().build(config);
        try (SqlSession session = inventorySessions.openSession(false)) {
            new MasterdataService(session, Clock.fixed(NOW, ZoneOffset.UTC)).createWarehouse("WH-A", "ENT-1", "SHA",
                    "上海仓", "Asia/Shanghai");
            new MasterdataService(session, Clock.fixed(NOW, ZoneOffset.UTC)).createLocation("LOC-1", "GATE-1", "ENT-1",
                    "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"), "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (inboundDb != null) {
            inboundDb.stop();
        }
        if (inventoryDb != null) {
            inventoryDb.stop();
        }
        if (outboundDb != null) {
            outboundDb.stop();
        }
    }

    @Test
    void inboundReceiveLoopAndOutboundCancelTombstone() {
        inboundJdbc.update("INSERT INTO source_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, "
                + "attempt_no, state, version, created_at, updated_at) VALUES ('EFF-IN','ENT-1','WH-A','wms-inbound',"
                + "'RECEIVE','RECEIPT_PART','RCPT-L','PART-L','LINE-L','EFF-IN','CMD-IN',NULL,1,'PENDING',0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
        inboundJdbc.update("INSERT INTO source_command (id, enterprise_id, warehouse_id, command_id, source_operation_id, "
                + "source_execution_id, business_effect_key, action, execution_attempt_id, attempt_no, payload_digest, "
                + "digest_version, payload_json, state, version, created_at, updated_at) VALUES ('CMD-IN','ENT-1','WH-A',"
                + "'CMD-IN','CMD-IN','EXEC-IN','EFF-IN','RECEIVE','CMD-IN',1,? ,1, CAST('{\"qty\":\"2\"}' AS JSON),"
                + "'PENDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", "a".repeat(64));
        inboundJdbc.update("INSERT INTO source_execution (id, enterprise_id, warehouse_id, command_id, action, "
                + "physical_status, stock_sync_status, physical_qty, posted_qty, actor_id, executed_at, version, created_at, "
                + "updated_at) VALUES ('EXEC-IN','ENT-1','WH-A','CMD-IN','RECEIVE','EXECUTED','PENDING',2,0,'ACTOR',"
                + "CURRENT_TIMESTAMP(6),0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
        inboundJdbc.update("INSERT INTO source_outbox (event_id, enterprise_id, warehouse_id, command_id, event_type, "
                + "payload, status, next_attempt_at, version, created_at, updated_at) VALUES ('OUT-IN','ENT-1','WH-A',"
                + "'CMD-IN','StockCommandRequested', CAST('{\"qty\":\"2\"}' AS JSON),'PENDING',CURRENT_TIMESTAMP(6),0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-LOOP", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Map<String, Object> applied;
        try (SqlSession session = inventorySessions.openSession(false)) {
            applied = new StockCommandService(session, clock).applyReceive("ENT-1", "WH-A",
                    StockCommandCodes.SOURCE_INBOUND, "CMD-IN", "RCPT-L", "PART-L", "LINE-L", "DOC-L", "ACTOR", "EXEC-IN",
                    bucket, Quantity.parse("2", 0));
            session.commit();
        }
        assertEquals(StockCommandCodes.CMD_APPLIED, applied.get("state"));
        inboundJdbc.update("INSERT INTO source_inbox (event_id, enterprise_id, warehouse_id, command_id, event_type, "
                + "payload, consumed_at, version, created_at, updated_at) VALUES ('INB-IN','ENT-1','WH-A','CMD-IN',"
                + "'InventoryCommandResult', CAST('{\"state\":\"APPLIED\"}' AS JSON),CURRENT_TIMESTAMP(6),0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))");
        inboundJdbc.update("UPDATE source_command SET state='APPLIED', posting_id='POST-IN' WHERE command_id='CMD-IN'");
        inboundJdbc.update("UPDATE source_execution SET stock_sync_status='APPLIED', posted_qty=2 WHERE command_id='CMD-IN'");

        assertEquals("APPLIED", inboundJdbc.queryForObject("SELECT state FROM source_command WHERE command_id='CMD-IN'",
                String.class));
        assertEquals(0, inventoryJdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-LOOP'",
                BigDecimal.class).compareTo(new BigDecimal("2.000000")));

        outboundJdbc.update("INSERT INTO source_command (id, enterprise_id, warehouse_id, command_id, source_operation_id, "
                + "source_execution_id, business_effect_key, action, execution_attempt_id, attempt_no, payload_digest, "
                + "digest_version, payload_json, state, version, created_at, updated_at) VALUES ('CMD-OUT','ENT-1','WH-A',"
                + "'CMD-OUT','CMD-OUT','EXEC-OUT','EFF-OUT','SHIP','CMD-OUT',1,? ,1, CAST('{\"qty\":\"1\"}' AS JSON),"
                + "'PENDING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", "b".repeat(64));
        String openEffect = UUID.randomUUID().toString();
        inventoryJdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, "
                + "attempt_no, state, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL,0,'REGISTERED',0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", openEffect, "ENT-1", "WH-A",
                StockCommandCodes.SOURCE_OUTBOUND, EffectCodes.ACTION_SHIP, EffectCodes.FACT_SHIPMENT_PART, "SHIP-L",
                "PART-L", "LINE-L", openEffect);
        try (SqlSession session = inventorySessions.openSession(false)) {
            StockBucketKey shipBucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-LOOP",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            String digest = CommandDigest.v1(EffectCodes.ACTION_SHIP, "DOC-OUT", shipBucket, "1", "CMD-OUT");
            Map<String, Object> cancelled = new StockCommandService(session, clock).cancel("ENT-1", "WH-A",
                    StockCommandCodes.SOURCE_OUTBOUND, "CMD-OUT", EffectCodes.ACTION_SHIP, openEffect, digest);
            assertEquals(StockCommandCodes.CMD_CANCELLED, cancelled.get("state"));
            session.commit();
        }
        outboundJdbc.update("UPDATE source_command SET state='CANCELLED' WHERE command_id='CMD-OUT'");
        assertEquals("CANCELLED", outboundJdbc.queryForObject("SELECT state FROM source_command WHERE command_id='CMD-OUT'",
                String.class));
        assertEquals("CANCELLED", inventoryJdbc.queryForObject("SELECT state FROM stock_command WHERE command_id='CMD-OUT'",
                String.class));
    }

    private static MySQLContainer start(String database) {
        MySQLContainer mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName(database)
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        return mysql;
    }

    private static DataSource dataSource(MySQLContainer mysql) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }

    private static JdbcTemplate jdbc(MySQLContainer mysql) {
        return new JdbcTemplate(dataSource(mysql));
    }
}
