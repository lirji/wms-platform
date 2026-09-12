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
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("inbound", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
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

    @Test
    void sameFactDifferentKeyReusesCommandAndSafeCloseAllowsNext() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SourceProtocolService service = new SourceProtocolService(session, clock);
            Map<String, Object> first = service.submitReceive("ENT-1", "WH-A", "CMD-F1", "RCPT-F", "PART-F", "LINE-F",
                    "ACTOR", new BigDecimal("3"));
            Map<String, Object> swapped = service.submitReceive("ENT-1", "WH-A", "CMD-F2", "RCPT-F", "PART-F", "LINE-F",
                    "ACTOR", new BigDecimal("3"));
            assertEquals("CMD-F1", first.get("commandId"));
            assertEquals("CMD-F1", swapped.get("commandId"));
            Map<String, Object> secondPart = service.submitReceive("ENT-1", "WH-A", "CMD-F3", "RCPT-F", "PART-F2",
                    "LINE-F", "ACTOR", new BigDecimal("1"));
            assertEquals("CMD-F3", secondPart.get("commandId"));
            assertNotEquals(first.get("effectId"), secondPart.get("effectId"));
            service.safeClose("ENT-1", "WH-A", "CMD-F1");
            assertThrows(com.lrj.wms.runtime.messaging.MessageRejectedException.class, () -> service.consumeResult(
                    "ENT-1", "WH-A", "EVT-CLOSED", "CMD-F1", "APPLIED", "POST-CLOSED", new BigDecimal("3")));
            Map<String, Object> next = service.submitReceive("ENT-1", "WH-A", "CMD-F4", "RCPT-F", "PART-F", "LINE-F",
                    "ACTOR", new BigDecimal("3"), "CMD-F1");
            assertEquals("CMD-F4", next.get("commandId"));
            assertThrows(com.lrj.wms.runtime.messaging.MessageRejectedException.class, () -> service.consumeResult("ENT-1", "WH-A", "EVT-OLD", "CMD-F1", "APPLIED", "POST-OLD", new BigDecimal("3")));
            service.consumeResult("ENT-1", "WH-A", "EVT-NEW", "CMD-F4", "APPLIED", "POST-NEW", new BigDecimal("3"));
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='CMD-F1'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_command WHERE command_id IN ('CMD-F1','CMD-F4') AND business_effect_key=("
                        + "SELECT id FROM source_effect WHERE fact_parent_id='RCPT-F' AND fact_part_id='PART-F')",
                Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT attempt_no FROM source_command WHERE command_id='CMD-F4'", Long.class));
        assertEquals("CMD-F4", jdbc.queryForObject(
                "SELECT applied_command_id FROM source_effect WHERE fact_parent_id='RCPT-F' AND fact_part_id='PART-F'",
                String.class));
        System.out.println("S5_REAUTH: inbound late old receipt does not take effect applied_command_id");
    }
}
