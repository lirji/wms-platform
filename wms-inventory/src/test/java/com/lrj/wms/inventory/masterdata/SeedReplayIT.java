package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 种子复跑不新增行；使用专属容器，避免与其它 IT 的仓主键冲突。 */
class SeedReplayIT {
    private static MySQLContainer mysql;
    private static DataSource dataSource;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        dataSource = source;
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void replayKeepsStableCounts() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-10T13:00:00Z"), ZoneOffset.UTC);
        Set<String> warehouses = Set.of(SeedCatalog.WAREHOUSE_A, SeedCatalog.WAREHOUSE_B);
        Map<String, Integer> first = SeedLocal.seed(dataSource, clock, warehouses);
        Map<String, Integer> second = SeedLocal.seed(dataSource, clock, warehouses);
        assertEquals(first, second);
        assertEquals(2, first.get("warehouses"));
        assertEquals(5, first.get("skus"));
        assertEquals(6, first.get("skuUnits"));
        assertEquals(6, first.get("lots"));
        assertEquals(8, first.get("grants"));
        assertCasePackAndExpiry(clock);
    }

    private static void assertCasePackAndExpiry(Clock clock) {
        SkuPolicy lotSku = SeedCatalog.skus().get(1).policy();
        assertEquals(new BigDecimal("12"),
                lotSku.toBaseQuantity(BigDecimal.ONE, SeedCatalog.twelve(), BigDecimal.ONE));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement units = connection.prepareStatement(
                        "SELECT numerator, denominator FROM sku_unit WHERE enterprise_id=? AND sku_id='SKU-LOT' AND unit_code='CS'");
                PreparedStatement near = connection.prepareStatement(
                        "SELECT expires_at, produced_at FROM lot WHERE enterprise_id=? AND warehouse_id='WH-A' AND lot_code='LOT-NEAR'");
                PreparedStatement expired = connection.prepareStatement(
                        "SELECT expires_at FROM lot WHERE enterprise_id=? AND warehouse_id='WH-A' AND lot_code='LOT-EXP'");
                PreparedStatement std = connection.prepareStatement(
                        "SELECT expires_at, produced_at FROM lot WHERE enterprise_id=? AND warehouse_id='WH-A' AND lot_code='LOT-STD'")) {
            for (PreparedStatement statement : List.of(units, near, expired, std)) {
                statement.setString(1, SeedCatalog.ENTERPRISE);
            }
            try (ResultSet unitRow = units.executeQuery()) {
                assertTrue(unitRow.next());
                assertEquals(0, new BigDecimal("12").compareTo(unitRow.getBigDecimal("numerator")));
                assertEquals(0, BigDecimal.ONE.compareTo(unitRow.getBigDecimal("denominator")));
            }
            try (ResultSet nearRow = near.executeQuery()) {
                assertTrue(nearRow.next());
                assertEquals(SeedCatalog.nearExpiry(clock.instant()), nearRow.getTimestamp("expires_at").toInstant());
                assertEquals("2026-09-17T13:00:00Z", nearRow.getTimestamp("expires_at").toInstant().toString());
            }
            try (ResultSet expiredRow = expired.executeQuery()) {
                assertTrue(expiredRow.next());
                assertEquals(SeedCatalog.alreadyExpired(clock.instant()),
                        expiredRow.getTimestamp("expires_at").toInstant());
                assertEquals("2026-09-09T13:00:00Z", expiredRow.getTimestamp("expires_at").toInstant().toString());
            }
            try (ResultSet stdRow = std.executeQuery()) {
                assertTrue(stdRow.next());
                assertEquals(null, stdRow.getTimestamp("expires_at"));
                assertEquals(null, stdRow.getTimestamp("produced_at"));
            }
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
