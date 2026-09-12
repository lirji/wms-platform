package com.lrj.wms.inventory.compat;

import com.lrj.wms.inventory.effect.EffectService;
import com.lrj.wms.inventory.effect.domain.EffectCodes;
import com.lrj.wms.inventory.effect.domain.RequestDigest;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
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
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.query.InventoryProjectionService;
import com.lrj.wms.inventory.query.ProjectionMapper;
import com.lrj.wms.inventory.recon.WarehouseQuantityFact;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S9-03：事件/快照/摘要 N/N-1 共存；未知版本拒绝。不是生产滚动升级现场。 */
class CompatibilityMatrixIT {
    private static final Instant NOW = Instant.parse("2026-09-12T11:00:00Z");
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
        Configuration config = new Configuration(new Environment("compat", new JdbcTransactionFactory(), source));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(ProjectionMapper.class);
        config.addMapper(EffectMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @AfterEach
    void resetObserve() {
        System.clearProperty(CompatibilityGate.OBSERVE_PROPERTY);
        CompatibilityGate.resetObservation();
    }

    @Test
    void nMinusOneEventAndSnapshotRemainCompatible() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        System.setProperty(CompatibilityGate.OBSERVE_PROPERTY, "true");
        CompatibilityGate.resetObservation();
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-N", "DOC-N", "ACTOR", bucket(), Quantity.parse("3", 0));
            session.commit();
        }
        String currentPayload = jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE operation_id='OP-N'", String.class);
        assertEquals(1, CompatibilityGate.readSchemaVersion(currentPayload, 0));
        assertTrue(currentPayload.contains("onHandAfter"));
        try (SqlSession session = sessions.openSession(false)) {
            InventoryProjectionService projection = new InventoryProjectionService(session, clock);
            Map<String, Object> n1 = projection.apply("ENT-1", "WH-A", "EVT-N1", "BAL-N1", 1,
                    InventoryCodes.EVENT_BALANCE_CHANGED, "{\"onHandAfter\":\"7\",\"reservedAfter\":\"0\"}",
                    Timestamp.from(NOW), "OWNER-1", "LOC-1", "SKU-N", MasterdataCodes.NO_LOT,
                    InventoryCodes.QUALITY_GOOD);
            assertEquals(Boolean.TRUE, n1.get("applied"));
            Map<String, Object> extra = projection.apply("ENT-1", "WH-A", "EVT-N", "BAL-N", 1,
                    InventoryCodes.EVENT_BALANCE_CHANGED,
                    "{\"schemaVersion\":1,\"onHandAfter\":\"4\",\"reservedAfter\":\"0\",\"traceNote\":\"ignore\"}",
                    Timestamp.from(NOW), "OWNER-1", "LOC-1", "SKU-N", MasterdataCodes.NO_LOT,
                    InventoryCodes.QUALITY_GOOD);
            assertEquals(Boolean.TRUE, extra.get("applied"));
            JobRunException unknown = assertThrows(JobRunException.class,
                    () -> projection.apply("ENT-1", "WH-A", "EVT-N2", "BAL-N2", 1,
                            InventoryCodes.EVENT_BALANCE_CHANGED,
                            "{\"schemaVersion\":2,\"onHandAfter\":\"9\",\"reservedAfter\":\"0\"}",
                            Timestamp.from(NOW), "OWNER-1", "LOC-1", "SKU-N", MasterdataCodes.NO_LOT,
                            InventoryCodes.QUALITY_GOOD));
            assertEquals("SCHEMA_UNSUPPORTED", unknown.code());
            session.rollback();
        }
        assertTrue(CompatibilityGate.acceptedNMinusOne() >= 1);
        assertTrue(CompatibilityGate.acceptedCurrent() >= 1);
        assertTrue(CompatibilityGate.rejectedUnknown() >= 1);

        Map<String, Object> fact = WarehouseQuantityFact.onHand("F-N", "ENT-1", "WH-A", "OWNER-1", "SKU-N",
                MasterdataCodes.NO_LOT, null, new BigDecimal("3"), "EA", "C-N", "WM-N");
        fact.put("optionalFlag", "keep");
        CompatibilityGate.requireQuantityFact(fact);
        Map<String, Object> legacy = new LinkedHashMap<>(fact);
        legacy.remove("schemaVersion");
        CompatibilityGate.requireQuantityFact(legacy);
        fact.put("schemaVersion", 2);
        assertEquals("SCHEMA_UNSUPPORTED",
                assertThrows(JobRunException.class, () -> CompatibilityGate.requireQuantityFact(fact)).code());
        System.out.println("S9_COMPAT: N-1 event/snapshot accepted; extras ignored; unknown schema rejected");
    }

    @Test
    void storedDigestVersionReplayAndFactIdentityStayUnique() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String effectId = UUID.randomUUID().toString();
        String canonical = RequestDigest.canonical(RequestDigest.VERSION_1, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, "SES-N", "PART-N", "LINE-N", "12", "EA");
        String digest = RequestDigest.digest(RequestDigest.VERSION_1, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, "SES-N", "PART-N", "LINE-N", "12", "EA");
        jdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, "
                + "attempt_no, state, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL,1,"
                + "'OPEN',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", effectId, "ENT-1", "WH-A",
                EffectCodes.SOURCE_INVENTORY, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART, "SES-N",
                "PART-N", "LINE-N", effectId);
        jdbc.update("INSERT INTO stock_effect_attempt (id, enterprise_id, warehouse_id, effect_id, command_id, "
                + "previous_command_id, attempt_no, state, digest_version, intent_digest, canonical_request, version, "
                + "created_at, updated_at) VALUES (?,?,?,?,?,NULL,1,'OPEN',1,?,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "ENT-1", "WH-A", effectId, "CMD-N1", digest, canonical);
        try (SqlSession session = sessions.openSession(false)) {
            EffectService effects = new EffectService(session, clock);
            assertTrue(effects.replayMatchesStored("ENT-1", "WH-A", effectId, 1));
            session.rollback();
        }
        assertNotEquals(digest, RequestDigest.digest(RequestDigest.VERSION_2, EffectCodes.ACTION_RECEIVE,
                EffectCodes.FACT_RECEIPT_PART, "SES-N", "PART-N", "LINE-N", "12", "EA"));
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                        + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, "
                        + "applied_command_id, attempt_no, state, version, created_at, updated_at) VALUES "
                        + "('DUP-N','ENT-1','WH-A',?,?,?,?,?,?,'DUP-N',NULL,NULL,0,'REGISTERED',0,"
                        + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                EffectCodes.SOURCE_INVENTORY, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART, "SES-N",
                "PART-N", "LINE-N"));
        System.out.println("S9_COMPAT: v1 digest replay stable; migrated fact identity unique");
    }

    private static StockBucketKey bucket() {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-N", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }
}
