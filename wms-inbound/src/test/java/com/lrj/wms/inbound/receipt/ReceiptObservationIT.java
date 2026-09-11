package com.lrj.wms.inbound.receipt;

import com.lrj.wms.inbound.protocol.SourceMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

/** S3-05：观察重放不入账，合法第二批累计，缺身份隔离。 */
class ReceiptObservationIT {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
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
        config.addMapper(InboundReceiptMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void replayAndSecondPartHonorParentQuota() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InboundReceiptService service = new InboundReceiptService(session, clock);
            service.createOrder("ENT-1", "WH-A", "ASN-OBS", "OMS", "EXT-OBS", "OWNER-1",
                    List.of(Map.of("lineId", "LINE-OBS", "externalLineId", "L1", "skuId", "SKU-1", "expectedQty",
                            new BigDecimal("10"), "unit", "EA")));
            Map<String, Object> first = service.receiveObserved("ENT-1", "WH-A", "ASN-OBS", "LINE-OBS", "SESS-1", "PART-1",
                    "CMD-O1", "DEV-1", "DSESS-1", 1L, "ACTOR", new BigDecimal("6"));
            Map<String, Object> replay = service.receiveObserved("ENT-1", "WH-A", "ASN-OBS", "LINE-OBS", "SESS-1", "PART-1",
                    "CMD-O1B", "DEV-1", "DSESS-1", 1L, "ACTOR", new BigDecimal("6"));
            assertEquals("CMD-O1", first.get("commandId"));
            assertEquals("CMD-O1", replay.get("commandId"));
            assertEquals(Boolean.TRUE, replay.get("replayed"));
            Map<String, Object> recovered = service.getObservation("ENT-1", "WH-A", "DEV-1", "DSESS-1", 1L);
            assertEquals("CMD-O1", recovered.get("commandId"));
            Map<String, Object> second = service.receiveObserved("ENT-1", "WH-A", "ASN-OBS", "LINE-OBS", "SESS-1", "PART-2",
                    "CMD-O2", "DEV-1", "DSESS-1", 2L, "ACTOR", new BigDecimal("4"));
            assertEquals("CMD-O2", second.get("commandId"));
            assertNotEquals(first.get("effectId"), second.get("effectId"));
            InboundException over = assertThrows(InboundException.class,
                    () -> service.receiveObserved("ENT-1", "WH-A", "ASN-OBS", "LINE-OBS", "SESS-1", "PART-3", "CMD-O3",
                            "DEV-1", "DSESS-1", 3L, "ACTOR", new BigDecimal("1")));
            assertEquals("OVER_RECEIVE", over.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT received_physical_qty FROM inbound_line WHERE id='LINE-OBS'",
                BigDecimal.class).compareTo(new BigDecimal("10.000000")));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_receipt_part WHERE inbound_line_id='LINE-OBS'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_observation_binding WHERE inbound_line_id='LINE-OBS' AND state='BOUND'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='CMD-O1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='CMD-O1B'", Integer.class));
    }

    @Test
    void conflictDigestMissingIdentityAndResetSessionReusePart() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InboundReceiptService service = new InboundReceiptService(session, clock);
            service.createOrder("ENT-1", "WH-A", "ASN-ISO", "OMS", "EXT-ISO", "OWNER-1",
                    List.of(Map.of("lineId", "LINE-ISO", "externalLineId", "L2", "skuId", "SKU-1", "expectedQty",
                            new BigDecimal("8"), "unit", "EA")));
            InboundException missing = assertThrows(InboundException.class,
                    () -> service.receiveObserved("ENT-1", "WH-A", "ASN-ISO", "LINE-ISO", "SESS-2", "PART-A", "CMD-M",
                            " ", "DSESS-2", 1L, "ACTOR", new BigDecimal("3")));
            assertEquals("AMBIGUOUS_OBSERVATION", missing.code());
            Map<String, Object> first = service.receiveObserved("ENT-1", "WH-A", "ASN-ISO", "LINE-ISO", "SESS-2", "PART-A",
                    "CMD-A1", "DEV-2", "DSESS-2", 1L, "ACTOR", new BigDecimal("3"));
            assertEquals("CMD-A1", first.get("commandId"));
            InboundException conflict = assertThrows(InboundException.class,
                    () -> service.receiveObserved("ENT-1", "WH-A", "ASN-ISO", "LINE-ISO", "SESS-2", "PART-A", "CMD-A2",
                            "DEV-2", "DSESS-2", 1L, "ACTOR", new BigDecimal("4")));
            assertEquals("OBSERVATION_CONFLICT", conflict.code());
            Map<String, Object> reset = service.receiveObserved("ENT-1", "WH-A", "ASN-ISO", "LINE-ISO", "SESS-2", "PART-A",
                    "CMD-A3", "DEV-2", "DSESS-RESET", 1L, "ACTOR", new BigDecimal("3"));
            assertEquals("CMD-A1", reset.get("commandId"));
            assertEquals(Boolean.TRUE, reset.get("reusedPart"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT received_physical_qty FROM inbound_line WHERE id='LINE-ISO'",
                BigDecimal.class).compareTo(new BigDecimal("3.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_receipt_part WHERE inbound_line_id='LINE-ISO'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='CMD-M'", Integer.class));
    }
}
