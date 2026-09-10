package com.lrj.wms.inventory.masterdata;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(6, first.get("lots"));
        assertEquals(8, first.get("grants"));
    }
}
