package com.lrj.wms.inbound.protocol;

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

/** 入库 T1/T3 与恢复查询。不连库存库。 */
class InboundProtocolIT {
    private static final Instant NOW = Instant.parse("2026-09-11T03:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inbound")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("inbound", new JdbcTransactionFactory(), source));
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
    void t1ReplayAndT3DoesNotDoublePost() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SourceProtocolService service = new SourceProtocolService(session, clock);
            Map<String, Object> first = service.submitReceive("ENT-1", "WH-A", "CMD-IN", "RCPT-1", "PART-1", "LINE-1",
                    "ACTOR", new BigDecimal("4"));
            Map<String, Object> replay = service.submitReceive("ENT-1", "WH-A", "CMD-IN", "RCPT-1", "PART-1", "LINE-1",
                    "ACTOR", new BigDecimal("4"));
            assertEquals("PENDING", first.get("state"));
            assertEquals(first.get("commandId"), replay.get("commandId"));
            session.commit();
        }
        try (SqlSession session = sessions.openSession(false)) {
            SourceProtocolService service = new SourceProtocolService(session, clock);
            service.consumeResult("ENT-1", "WH-A", "EVT-1", "CMD-IN", "APPLIED", "POST-1", new BigDecimal("4"));
            service.consumeResult("ENT-1", "WH-A", "EVT-1", "CMD-IN", "APPLIED", "POST-1", new BigDecimal("4"));
            assertEquals("APPLIED", service.get("ENT-1", "WH-A", "CMD-IN").get("state"));
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_inbox WHERE event_id='EVT-1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT posted_qty FROM source_execution WHERE command_id='CMD-IN'",
                BigDecimal.class).compareTo(new BigDecimal("4.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_outbox WHERE command_id='CMD-IN'", Integer.class));
    }
}
