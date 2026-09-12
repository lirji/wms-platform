package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
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

/** S5-03：STARTED 后才能占用；UNKNOWN 不释放；逆向不超过原 posting。 */
class ExecutionPermitIT {
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
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
        Configuration config = new Configuration(new Environment("permit", new JdbcTransactionFactory(), source));
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
    void startedUnknownKeepsOccupancyAndReverseIsBounded() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            Map<String, Object> started = service.startPermit("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-P1", "TASK-1", 1L, "ORD-1", "TASK-1", "LINE-1", new BigDecimal("3"));
            assertEquals("STARTED", started.get("permitState"));
            Map<String, Object> replay = service.startPermit("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-P1", "TASK-1", 1L, "ORD-1", "TASK-1", "LINE-1", new BigDecimal("3"));
            assertEquals("CMD-P1", replay.get("commandId"));
            Map<String, Object> reused = service.startPermit("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-P2", "TASK-1", 1L, "ORD-1", "TASK-1", "LINE-1", new BigDecimal("3"));
            assertEquals("CMD-P1", reused.get("commandId"));
            Map<String, Object> unknown = service.markUnknown("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-P1");
            assertEquals("UNKNOWN", unknown.get("permitState"));
            InventoryException occupying = assertThrows(InventoryException.class,
                    () -> service.cancel("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND, "CMD-P1", "PICK",
                            String.valueOf(unknown.get("businessEffectKey")), "x"));
            assertEquals("PERMIT_UNKNOWN", occupying.code());
            session.commit();
        }
        assertEquals("UNKNOWN", jdbc.queryForObject(
                "SELECT state FROM execution_permit WHERE command_id='CMD-P1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM execution_permit WHERE command_id='CMD-P1'",
                Integer.class));

        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-R", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        String postingId;
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            service.applyReceive("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-R", "RCPT-R", "PART-R",
                    "LINE-R", "DOC-R", "ACTOR", "EXEC-R", bucket, Quantity.parse("5", 0));
            session.commit();
        }
        postingId = jdbc.queryForObject("SELECT id FROM stock_posting WHERE command_id='CMD-R'", String.class);
        try (SqlSession session = sessions.openSession(false)) {
            StockCommandService service = new StockCommandService(session, clock);
            service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RV1", "CASE-R", "CP-1",
                    "CL-1", postingId, "DOC-RV", Quantity.parse("4", 0));
            InventoryException over = assertThrows(InventoryException.class,
                    () -> service.applyCompensate("ENT-1", "WH-A", StockCommandCodes.SOURCE_INBOUND, "CMD-RV2",
                            "CASE-R", "CP-2", "CL-2", postingId, "DOC-RV2", Quantity.parse("2", 0)));
            assertEquals("OVER_REVERSE", over.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT reversed_qty FROM stock_posting WHERE id=?", BigDecimal.class,
                postingId).compareTo(new BigDecimal("4.000000")));
        System.out.println("S5_PERMIT: STARTED replay; UNKNOWN occupies; reverse bounded by original posting");
    }
}
