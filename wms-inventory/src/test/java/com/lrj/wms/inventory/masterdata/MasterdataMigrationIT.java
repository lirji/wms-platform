package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
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

/** 真实 MySQL 8.4 执行 V001 迁移；Docker 不可用直接失败。 */
class MasterdataMigrationIT {
    private static MySQLContainer mysql;
    private static DataSource dataSource;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        dataSource = source();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("masterdata", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MasterdataMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void tablesHaveChineseComments() {
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history'");
        assertEquals(15, tables.size());
        for (Map<String, Object> table : tables) {
            assertFalse(String.valueOf(table.get("TABLE_COMMENT")).isBlank(), () -> table.get("TABLE_NAME") + " 缺表注释");
        }
        Integer missing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME<>'flyway_schema_history' AND (COLUMN_COMMENT IS NULL OR COLUMN_COMMENT='')",
                Integer.class);
        assertEquals(0, missing);
    }

    @Test
    void servicePersistsWarehouseLocationSkuAndLot() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T13:00:00Z"), ZoneOffset.UTC);
        try (var session = sessions.openSession(false)) {
            var service = new MasterdataService(session, clock);
            service.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            service.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            SkuPolicy sku = SkuPolicy.create("SKU-LOT", "ENT-1", "SKU-LOT", "批次商品", "EA", 0, true, false, true, 1,
                    MasterdataCodes.STATE_ACTIVE);
            service.createSku(sku, "UNIT-EA");
            service.addSkuUnit(sku, "UNIT-CS", "CS", new BigDecimal("12"), BigDecimal.ONE, BigDecimal.ONE);
            service.createLot(sku, "LOT-1", "WH-A", "OWNER-1", "SUP-001", "ENT-1/OWNER-1/SKU-LOT/SUP-001",
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), "2026-12-31", 0);
            session.commit();
        }
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT state FROM location_gate WHERE enterprise_id='ENT-1' AND location_id='LOC-1'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT quantity_scale FROM sku WHERE id='SKU-LOT'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sku_unit WHERE sku_id='SKU-LOT'", Integer.class));
    }

    @Test
    void databaseRejectsSerialFractionalScaleAndUnpairedCapacity() {
        jdbc.update("INSERT INTO sku (id, enterprise_id, code, name, base_unit, quantity_scale, lot_enabled, serial_enabled, "
                + "expiry_enabled, policy_version, state, created_at, updated_at) VALUES ('SKU-BAD', 'ENT-2', 'BAD', 'x', 'EA', 0, "
                + "0, 0, 0, 1, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE sku SET serial_enabled=1, quantity_scale=2 WHERE id='SKU-BAD'"));
        jdbc.update("INSERT INTO warehouse (id, enterprise_id, warehouse_id, code, name, timezone, state, created_at, updated_at) "
                + "VALUES ('WH-B', 'ENT-2', 'WH-B', 'B', 'b', 'UTC', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO location (id, enterprise_id, warehouse_id, code, zone_code, location_type, capacity_qty, capacity_unit, "
                        + "state, created_at, updated_at) VALUES ('LOC-BAD', 'ENT-2', 'WH-B', 'X', 'Z', 'STORAGE', 10, NULL, 'ACTIVE', "
                        + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"));
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO lot (id, enterprise_id, warehouse_id, owner_id, sku_id, lot_code, business_lot_key, expiry_rule_version, "
                        + "created_at, updated_at) VALUES ('NO_LOT', 'ENT-2', 'WH-B', 'O', 'SKU-BAD', 'L1', 'K', 0, CURRENT_TIMESTAMP(6), "
                        + "CURRENT_TIMESTAMP(6))"));
    }

    @Test
    void duplicateWarehouseCodeIsRejected() {
        jdbc.update("INSERT INTO warehouse (id, enterprise_id, warehouse_id, code, name, timezone, state, created_at, updated_at) "
                + "VALUES ('WH-C', 'ENT-3', 'WH-C', 'DUP', 'c', 'UTC', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))");
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO warehouse (id, enterprise_id, warehouse_id, code, name, timezone, state, created_at, updated_at) "
                        + "VALUES ('WH-D', 'ENT-3', 'WH-D', 'DUP', 'd', 'UTC', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))"));
    }

    private static DataSource source() {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }
}
