package com.lrj.wms.inbound.seed;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 入库演示单复跑不新增行。 */
class SeedInboundReplayIT {
    private static MySQLContainer mysql;
    private static DataSource dataSource;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inbound")
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
        Map<String, Integer> first = SeedInbound.seed(dataSource, clock);
        Map<String, Integer> second = SeedInbound.seed(dataSource, clock);
        assertEquals(first, second);
        assertEquals(4, first.get("orders"));
        assertEquals(5, first.get("lines"));
    }
}
