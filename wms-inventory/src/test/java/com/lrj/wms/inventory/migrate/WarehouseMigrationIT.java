package com.lrj.wms.inventory.migrate;

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

/** S9-02：两物理库全量+增量、停写、切 epoch、旧库拒写。不是生产停写窗口验收。 */
class WarehouseMigrationIT {
    private static MySQLContainer sourceMysql;
    private static MySQLContainer targetMysql;
    private static SqlSessionFactory sourceSessions;
    private static SqlSessionFactory targetSessions;
    private static JdbcTemplate sourceJdbc;
    private static JdbcTemplate targetJdbc;

    @BeforeAll
    static void prepare() {
        sourceMysql = mysql("wms_source");
        targetMysql = mysql("wms_target");
        sourceMysql.start();
        targetMysql.start();
        MysqlDataSource source = datasource(sourceMysql);
        MysqlDataSource target = datasource(targetMysql);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate();
        sourceJdbc = new JdbcTemplate(source);
        targetJdbc = new JdbcTemplate(target);
        sourceSessions = sessions("source", source);
        targetSessions = sessions("target", target);
        Clock clock = Clock.systemUTC();
        try (SqlSession session = sourceSessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-FULL", "DOC", "ACTOR",
                    bucket(), Quantity.parse("10", 0));
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (sourceMysql != null) {
            sourceMysql.stop();
        }
        if (targetMysql != null) {
            targetMysql.stop();
        }
    }

    @Test
    void twoPhysicalDatabasesSwitchEpochAndRejectOldWrites() {
        Clock clock = Clock.systemUTC();
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            assertEquals(ACTIVE_COPY, migrate.prepare("ENT-1", "WH-A", "CELL-A", "CELL-B").get("state"));
            Map<String, Object> full = migrate.copyFull("ENT-1", "WH-A");
            assertTrue(((Number) full.get("copiedRows")).intValue() >= 3);
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-INCR", "DOC", "ACTOR",
                    bucket(), Quantity.parse("2", 0));
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            migrate.copyIncremental("ENT-1", "WH-A");
            assertEquals(0, new BigDecimal("12").compareTo(targetJdbc.queryForObject(
                    "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A'", BigDecimal.class)));
            assertEquals(WarehouseMigrationService.QUIESCING, migrate.quiesce("ENT-1", "WH-A").get("state"));
            assertEquals(Boolean.TRUE, migrate.validate("ENT-1", "WH-A").get("validated"));
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            InventoryException blocked = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-OLD", "DOC",
                            "ACTOR", bucket(), Quantity.parse("1", 0)));
            assertEquals("STALE_ROUTE", blocked.code());
            session.rollback();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            Map<String, Object> switched = migrate.switchEpoch("ENT-1", "WH-A");
            assertEquals(2L, ((Number) switched.get("switchedEpoch")).longValue());
            assertEquals(WarehouseMigrationService.RETIRED, switched.get("state"));
            InventoryException rollback = assertThrows(InventoryException.class,
                    () -> migrate.refuseRollbackAfterCutover("ENT-1", "WH-A"));
            assertEquals("ROLLBACK_FORBIDDEN", rollback.code());
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            InventoryException retired = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-SRC", "DOC",
                            "ACTOR", bucket(), Quantity.parse("1", 0)));
            assertEquals("STALE_ROUTE", retired.code());
            session.rollback();
        }
        try (SqlSession session = targetSessions.openSession(false)) {
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-TGT", "DOC", "ACTOR",
                    bucket(), Quantity.parse("1", 0));
            session.commit();
        }
        assertEquals(0, new BigDecimal("13").compareTo(targetJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A'", BigDecimal.class)));
        assertEquals("RETIRED", sourceJdbc.queryForObject(
                "SELECT state FROM warehouse_route WHERE warehouse_id='WH-A'", String.class));
        System.out.println("S9_MIGRATE: two MySQL; incremental catch-up; epoch switch; old source writes denied");
    }

    @Test
    void abortBeforeSwitchReopensSourceWrites() {
        Clock clock = Clock.systemUTC();
        try (SqlSession session = sourceSessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-B", "ENT-1", "SHB", "回退仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-B", "GATE-B", "ENT-1", "WH-B", "B-01", "B", "STORAGE",
                    new BigDecimal("50"), "EA");
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-B", "OP-B1", "DOC", "ACTOR",
                    bucketB(), Quantity.parse("4", 0));
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            migrate.prepare("ENT-1", "WH-B", "CELL-A", "CELL-B");
            migrate.copyFull("ENT-1", "WH-B");
            migrate.quiesce("ENT-1", "WH-B");
            assertEquals(WarehouseMigrationService.ACTIVE, migrate.abortBeforeSwitch("ENT-1", "WH-B").get("state"));
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-B", "OP-B2", "DOC", "ACTOR",
                    bucketB(), Quantity.parse("1", 0));
            session.commit();
        }
        assertEquals(0, new BigDecimal("5").compareTo(sourceJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-B'", BigDecimal.class)));
        System.out.println("S9_MIGRATE: abort before cutover reopens source; not a post-cutover rollback");
    }

    private static final String ACTIVE_COPY = WarehouseMigrationService.ACTIVE;

    private static StockBucketKey bucket() {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-M", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static StockBucketKey bucketB() {
        return StockBucketKey.of("ENT-1", "WH-B", "OWNER-1", "LOC-B", "SKU-M", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static MySQLContainer mysql(String database) {
        return new MySQLContainer("mysql:8.4.11").withDatabaseName(database)
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
    }

    private static MysqlDataSource datasource(MySQLContainer mysql) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }

    private static SqlSessionFactory sessions(String name, MysqlDataSource source) {
        Configuration config = new Configuration(new Environment(name, new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(WarehouseRouteMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
