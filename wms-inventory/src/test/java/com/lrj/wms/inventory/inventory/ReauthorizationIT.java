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
 * S5-05：安全关闭独立证据、executionAttempt 重授权、迟到旧命令、补偿 DEFERRED。
 * 同 JVM 本库，不是 HTTP/设备，不能当作 AC-48/49/50 生产通过。
 */
class ReauthorizationIT {
    private static final Instant NOW = Instant.parse("2026-09-12T09:00:00Z");
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
        Configuration config = new Configuration(new Environment("reauth", new JdbcTransactionFactory(), source));
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
    void rejectedSafeCloseReauthAndLateOldCommand() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-RA", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        String effectId = UUID.randomUUID().toString();
        String digest = CommandDigest.v1(EffectCodes.ACTION_RECEIVE, "DOC-RA", bucket, "2", "CMD-RA1");
        jdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, "
                + "attempt_no, state, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,1,'OPEN',0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", effectId, "ENT-1", "WH-A",
                StockCommandCodes.SOURCE_INBOUND, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART, "RCPT-RA",
                "PART-RA", "LINE-RA", effectId, "CMD-RA1");
        jdbc.update("INSERT INTO stock_command (id, enterprise_id, warehouse_id, source_service, command_id, action, "
                + "business_effect_key, execution_attempt_id, attempt_no, previous_command_id, payload_digest, digest_version, "
                + "state, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,1,NULL,?,1,'PENDING',0,"
                + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", "CMD-RA1", "ENT-1", "WH-A",
                StockCommandCodes.SOURCE_INBOUND, "CMD-RA1", EffectCodes.ACTION_RECEIVE, effectId, "CMD-RA1", digest);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> rejected = service.reject("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RA1",
                    effectId);
            assertEquals(StockCommandCodes.CMD_REJECTED, rejected.get("state"));
            Map<String, Object> closed = service.safeClose("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RA1",
                    effectId);
            assertEquals(StockCommandCodes.CMD_REJECTED, closed.get("state"));
            assertNotNull(closed.get("safeCloseRef"));
            Map<String, Object> next = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RA2",
                    "RCPT-RA", "PART-RA", "LINE-RA", "DOC-RA2", "ACTOR", "EXEC-RA2", bucket, Quantity.parse("2", 0),
                    "CMD-RA1");
            assertEquals("CMD-RA2", next.get("commandId"));
            assertEquals(StockCommandCodes.CMD_APPLIED, next.get("state"));
            Map<String, Object> late = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RA1",
                    "RCPT-RA", "PART-RA", "LINE-RA", "DOC-RA", "ACTOR", "EXEC-RA1", bucket, Quantity.parse("2", 0));
            assertEquals("CMD-RA1", late.get("commandId"));
            assertEquals(StockCommandCodes.CMD_REJECTED, late.get("state"));
            session.commit();
        }
        assertEquals("REJECTED", jdbc.queryForObject("SELECT state FROM stock_command WHERE command_id='CMD-RA1'",
                String.class));
        assertNotNull(jdbc.queryForObject("SELECT safe_close_id FROM stock_command WHERE command_id='CMD-RA1'",
                String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='CMD-RA2'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='CMD-RA1'", Integer.class));
        System.out.println("S5_REAUTH: REJECTED kept; safeCloseRef independent; late old command does not post");
    }

    @Test
    void startedAndAppliedRefuseReauth() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-RB", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> started = service.startPermit("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-RB-P", "TASK-RB", 1L, "ORD-RB", "TASK-RB", "LINE-RB", new BigDecimal("2"));
            InventoryException occupying = assertThrows(InventoryException.class,
                    () -> service.safeClose("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND, "CMD-RB-P",
                            String.valueOf(started.get("businessEffectKey"))));
            assertEquals("STALE_EXECUTION_ATTEMPT", occupying.code());
            service.markUnknown("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND, "CMD-RB-P");
            InventoryException unknown = assertThrows(InventoryException.class,
                    () -> service.safeClose("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND, "CMD-RB-P",
                            String.valueOf(started.get("businessEffectKey"))));
            assertEquals("STALE_EXECUTION_ATTEMPT", unknown.code());
            Map<String, Object> applied = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-RB-R", "RCPT-RB", "PART-RB", "LINE-RB", "DOC-RB", "ACTOR", "EXEC-RB", bucket,
                    Quantity.parse("2", 0));
            InventoryException already = assertThrows(InventoryException.class,
                    () -> service.safeClose("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RB-R",
                            String.valueOf(applied.get("businessEffectKey"))));
            assertEquals("EFFECT_ALREADY_APPLIED", already.code());
            session.commit();
        }
        System.out.println("S5_REAUTH: STARTED/APPLIED refuse safe-close reauth");
    }

    @Test
    void compensateDeferredUntilOriginalPostingThenReplay() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String missingId = "POST-LATER";
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> deferred = service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-DEF", "CASE-D", "CP-D", "CL-D", missingId, "DOC-DEF", Quantity.parse("1", 0));
            assertEquals(StockCommandCodes.CMD_DEFERRED, deferred.get("state"));
            session.commit();
        }
        jdbc.update("INSERT INTO stock_posting (id, enterprise_id, warehouse_id, source_service, command_id, "
                + "business_effect_key, action, execution_attempt_id, posting_type, quantity, source_execution_id, "
                + "source_document_id, ledger_manifest, result_version, reversed_qty, version, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST('{}' AS JSON),1,0,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                missingId, "ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-SEED", missingId, "RECEIVE",
                "ATT-SEED", "RECEIPT", new BigDecimal("3"), StockCommandCodes.NO_SOURCE_EXECUTION, "DOC-SEED");
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> applied = service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-DEF", "CASE-D", "CP-D", "CL-D", missingId, "DOC-DEF", Quantity.parse("1", 0));
            Map<String, Object> replay = service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-DEF2", "CASE-D", "CP-D", "CL-D", missingId, "DOC-DEF", Quantity.parse("1", 0));
            assertEquals(StockCommandCodes.CMD_APPLIED, applied.get("state"));
            assertEquals("CMD-DEF", replay.get("commandId"));
            InventoryException over = assertThrows(InventoryException.class,
                    () -> service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-DEF3",
                            "CASE-D", "CP-D2", "CL-D2", missingId, "DOC-DEF3", Quantity.parse("3", 0)));
            assertEquals("OVER_REVERSE", over.code());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE posting_type='COMPENSATION' AND original_posting_id=?",
                Integer.class, missingId));
        assertEquals(0, jdbc.queryForObject("SELECT reversed_qty FROM stock_posting WHERE id=?", BigDecimal.class,
                missingId).compareTo(new BigDecimal("1.000000")));
        System.out.println("S5_REAUTH: compensate DEFERRED then apply once; extra part bounded");
    }
}
