package com.lrj.wms.outbound.protocol;

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

/** 出库 T1/T3。不连库存库。 */
class OutboundProtocolIT {
    private static final Instant NOW = Instant.parse("2026-09-11T03:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("outbound", new JdbcTransactionFactory(), source));
        config.addMapper(SourceMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void t1AndT3CancelledReceipt() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SourceProtocolService service = new SourceProtocolService(session, clock);
            Map<String, Object> first = service.submitShip("ENT-1", "WH-A", "CMD-OUT", "SHIP-1", "PART-1", "LINE-1",
                    "ACTOR", new BigDecimal("2"));
            assertEquals("PENDING", first.get("state"));
            service.consumeResult("ENT-1", "WH-A", "EVT-OUT", "CMD-OUT", "CANCELLED", null, BigDecimal.ZERO);
            assertEquals("CANCELLED", service.get("ENT-1", "WH-A", "CMD-OUT").get("state"));
            session.commit();
        }
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT stock_sync_status FROM source_execution WHERE command_id='CMD-OUT'",
                String.class));
    }
}
