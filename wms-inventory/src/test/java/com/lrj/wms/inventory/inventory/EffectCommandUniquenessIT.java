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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S2-07：同事实换键、安全关闭下一尝试、一效果一 posting、补偿不重复。 */
class EffectCommandUniquenessIT {
    private static final Instant NOW = Instant.parse("2026-09-11T06:00:00Z");
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
    void sameFactReusesCommandSafeCloseReauthAndOnePosting() {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-E", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Quantity qty = Quantity.parse("5", 0);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> first = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-A",
                    "RCPT-E", "PART-E", "LINE-E", "DOC-E", "ACTOR", "EXEC-E", bucket, qty);
            Map<String, Object> swapped = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-B",
                    "RCPT-E", "PART-E", "LINE-E", "DOC-E", "ACTOR", "EXEC-E", bucket, qty);
            assertEquals("CMD-A", first.get("commandId"));
            assertEquals("CMD-A", swapped.get("commandId"));
            assertEquals(StockCommandCodes.CMD_APPLIED, swapped.get("state"));
            InventoryException appliedClose = assertThrows(InventoryException.class,
                    () -> service.safeClose("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-A",
                            String.valueOf(first.get("businessEffectKey"))));
            assertEquals("EFFECT_ALREADY_APPLIED", appliedClose.code());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE business_effect_key=("
                + "SELECT business_effect_key FROM stock_command WHERE command_id='CMD-A')", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_command WHERE command_id IN ('CMD-A','CMD-B')",
                Integer.class));

        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> secondPart = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-C",
                    "RCPT-E", "PART-E2", "LINE-E", "DOC-E2", "ACTOR", "EXEC-E2", bucket, Quantity.parse("2", 0));
            assertEquals("CMD-C", secondPart.get("commandId"));
            session.commit();
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE source_service='wms-inbound' "
                + "AND warehouse_id='WH-A'", Integer.class));

        String openEffect;
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            String digest = CommandDigest.v1(EffectCodes.ACTION_RECEIVE, "DOC-Z", bucket, "1", "CMD-Z");
            openEffect = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, "
                    + "fact_parent_id, fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, "
                    + "attempt_no, state, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,NULL,NULL,0,"
                    + "'REGISTERED',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))", openEffect, "ENT-1", "WH-A",
                    StockCommandCodes.SOURCE_INBOUND, EffectCodes.ACTION_RECEIVE, EffectCodes.FACT_RECEIPT_PART, "RCPT-Z",
                    "PART-Z", "LINE-Z", openEffect);
            assertEquals(StockCommandCodes.CMD_CANCELLED, service.cancel("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-Z", EffectCodes.ACTION_RECEIVE, openEffect, digest).get("state"));
            Map<String, Object> reusedCancel = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-Z2", "RCPT-Z", "PART-Z", "LINE-Z", "DOC-Z", "ACTOR", "EXEC-Z", bucket, Quantity.parse("1", 0));
            assertEquals("CMD-Z", reusedCancel.get("commandId"));
            assertEquals(StockCommandCodes.CMD_CANCELLED, reusedCancel.get("state"));
            service.safeClose("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-Z", openEffect);
            Map<String, Object> next = service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-Z3",
                    "RCPT-Z", "PART-Z", "LINE-Z", "DOC-Z3", "ACTOR", "EXEC-Z3", bucket, Quantity.parse("1", 0), "CMD-Z");
            assertEquals("CMD-Z3", next.get("commandId"));
            assertEquals(StockCommandCodes.CMD_APPLIED, next.get("state"));
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='CMD-Z3'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='CMD-Z'", Integer.class));

        String originalPosting = jdbc.queryForObject("SELECT id FROM stock_posting WHERE command_id='CMD-A'", String.class);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> comp = service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-CMP",
                    "CASE-1", "CPART-1", "CLINE-1", originalPosting, "DOC-CMP", Quantity.parse("1", 0));
            Map<String, Object> compReplay = service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND,
                    "CMD-CMP2", "CASE-1", "CPART-1", "CLINE-1", originalPosting, "DOC-CMP", Quantity.parse("1", 0));
            assertEquals("CMD-CMP", comp.get("commandId"));
            assertEquals("CMD-CMP", compReplay.get("commandId"));
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE posting_type='COMPENSATION' AND original_posting_id=?",
                Integer.class, originalPosting));

        String effectA = jdbc.queryForObject("SELECT business_effect_key FROM stock_command WHERE command_id='CMD-A'",
                String.class);
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                "INSERT INTO stock_posting (id, enterprise_id, warehouse_id, source_service, command_id, business_effect_key, "
                        + "action, execution_attempt_id, posting_type, quantity, source_execution_id, source_document_id, "
                        + "ledger_manifest, result_version, reversed_qty, version, created_at, updated_at) VALUES "
                        + "('DUP-POST','ENT-1','WH-A','wms-inbound','CMD-DUP',?,'RECEIVE','ATT-DUP','RECEIPT',1,"
                        + "'NO_SOURCE_EXECUTION','DOC', CAST('{}' AS JSON),1,0,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                effectA));
    }
}
