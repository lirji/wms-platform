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

/** S3-01：收货/质检/上架实物与过账双累计，超收拒绝。 */
class InboundReceiptIT {
    private static final Instant NOW = Instant.parse("2026-09-11T07:00:00Z");
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
    void sameExternalCommandKeyIsIndependentAcrossWarehouses() {
        try (SqlSession session = sessions.openSession(false)) {
            var service = new InboundReceiptService(session, Clock.fixed(NOW, ZoneOffset.UTC));
            for (String warehouse : List.of("WH-KEY-A", "WH-KEY-B")) {
                service.createOrder("ENT-1", warehouse, "ORDER-" + warehouse, "OMS", "EXT", "OWNER",
                        List.of(Map.of("lineId", "LINE-" + warehouse, "externalLineId", "L1", "skuId", "SKU",
                                "expectedQty", new BigDecimal("3"), "unit", "EA")));
                var received = service.receive("ENT-1", warehouse, "ORDER-" + warehouse, "LINE-" + warehouse,
                        "SHARED-EXTERNAL-KEY", "PART", "ACTOR", new BigDecimal("3"));
                assertEquals(false, received.get("replayed"));
                assertEquals("SHARED-EXTERNAL-KEY", received.get("commandId"));
            }
            session.commit();
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='SHARED-EXTERNAL-KEY'", Integer.class));
    }

    @Test
    void receiveInspectPutawayTotalsAndRejectOverReceive() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InboundReceiptService service = new InboundReceiptService(session, clock);
            service.createOrder("ENT-1", "WH-A", "ASN-1", "OMS", "EXT-1", "OWNER-1",
                    List.of(Map.of("lineId", "LINE-1", "externalLineId", "L1", "skuId", "SKU-1", "expectedQty",
                            new BigDecimal("10"), "unit", "EA")));
            Map<String, Object> first = service.receive("ENT-1", "WH-A", "ASN-1", "LINE-1", "CMD-R1", "PART-1", "ACTOR",
                    new BigDecimal("6"));
            assertEquals("CMD-R1", first.get("commandId"));
            InboundException over = assertThrows(InboundException.class, () -> service.receive("ENT-1", "WH-A", "ASN-1",
                    "LINE-1", "CMD-R2", "PART-2", "ACTOR", new BigDecimal("5")));
            assertEquals("OVER_RECEIVE", over.code());
            assertThrows(com.lrj.wms.runtime.messaging.MessageRejectedException.class, () -> service.consumeReceive(
                    "ENT-1", "WH-A", "WRONG-LINE", "EVT-WRONG", "CMD-R1", "APPLIED", "POST-R1", new BigDecimal("6")));
            service.consumeReceive("ENT-1", "WH-A", "LINE-1", "EVT-R1", "CMD-R1", "APPLIED", "POST-R1",
                    new BigDecimal("6"));
            service.consumeReceive("ENT-1", "WH-A", "LINE-1", "EVT-R1", "CMD-R1", "APPLIED", "POST-R1",
                    new BigDecimal("6"));
            assertEquals(false, service.consumeReceive("ENT-1", "WH-A", "LINE-1", "EVT-R1-REDELIVERED", "CMD-R1",
                    "APPLIED", "POST-R1", new BigDecimal("6")).get("consumed"));
            Map<String, Object> qc = service.inspect("ENT-1", "WH-A", "INSP-1", "LINE-1", new BigDecimal("6"),
                    BigDecimal.ZERO, "QC", 1L);
            assertEquals("ACCEPTED", qc.get("resultCode"));
            Map<String, Object> putaway = service.putaway("ENT-1", "WH-A", "ASN-1", "LINE-1", "TASK-P1", "LOC-1",
                    InboundReceiptService.LOCATION_STORAGE, new BigDecimal("6"), "CMD-PUTAWAY", "PUTAWAY-ACTOR");
            assertEquals("CMD-PUTAWAY", service.putaway("ENT-1", "WH-A", "ASN-1", "LINE-1", "TASK-P1", "LOC-1",
                    InboundReceiptService.LOCATION_STORAGE, new BigDecimal("6"), "CMD-NEW-KEY", "SECOND-ACTOR").get("commandId"));
            assertThrows(com.lrj.wms.runtime.command.CommandConflictException.class, () -> service.putaway("ENT-1", "WH-A",
                    "ASN-1", "LINE-1", "TASK-P1", "LOC-CHANGED", InboundReceiptService.LOCATION_STORAGE,
                    new BigDecimal("6"), "CMD-PUTAWAY", "PUTAWAY-ACTOR"));
            service.consumePutaway("ENT-1", "WH-A", "LINE-1", "EVT-P1", String.valueOf(putaway.get("commandId")),
                    "APPLIED", "POST-P1", new BigDecimal("6"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT received_physical_qty FROM inbound_line WHERE id='LINE-1'", BigDecimal.class)
                .compareTo(new BigDecimal("6.000000")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT received_posted_qty FROM inbound_line WHERE id='LINE-1'", BigDecimal.class)
                .compareTo(new BigDecimal("6.000000")));
        assertEquals("PUTAWAY-ACTOR", jdbc.queryForObject("SELECT actor_id FROM source_execution WHERE command_id='CMD-PUTAWAY'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_execution WHERE command_id='CMD-PUTAWAY'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT putaway_physical_qty FROM inbound_line WHERE id='LINE-1'", BigDecimal.class)
                .compareTo(new BigDecimal("6.000000")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT putaway_posted_qty FROM inbound_line WHERE id='LINE-1'", BigDecimal.class)
                .compareTo(new BigDecimal("6.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_inspection WHERE id='INSP-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_task WHERE id='TASK-P1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id IN ('CMD-R1','CMD-PUTAWAY')",
                Integer.class));
    }

    @Test
    void rejectFailedQcAndWrongPutawayLocation() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InboundReceiptService service = new InboundReceiptService(session, clock);
            service.createOrder("ENT-1", "WH-A", "ASN-QC", "OMS", "EXT-QC", "OWNER-1",
                    List.of(Map.of("lineId", "LINE-QC", "externalLineId", "LQ", "skuId", "SKU-QC", "expectedQty",
                            new BigDecimal("5"), "unit", "EA")));
            service.receive("ENT-1", "WH-A", "ASN-QC", "LINE-QC", "CMD-QC", "PART-QC", "ACTOR", new BigDecimal("5"));
            InboundException beforeQc = assertThrows(InboundException.class,
                    () -> service.putaway("ENT-1", "WH-A", "ASN-QC", "LINE-QC", "TASK-NOQC", "LOC-1",
                            InboundReceiptService.LOCATION_STORAGE, new BigDecimal("5"), "CMD-TASK-NOQC", "PUTAWAY-ACTOR"));
            assertEquals("QC_REQUIRED", beforeQc.code());
            Map<String, Object> qc = service.inspect("ENT-1", "WH-A", "INSP-QC", "LINE-QC", BigDecimal.ZERO,
                    new BigDecimal("5"), "QC", 1L);
            assertEquals(InboundReceiptService.RESULT_REJECTED, qc.get("resultCode"));
            InboundException rejected = assertThrows(InboundException.class,
                    () -> service.putaway("ENT-1", "WH-A", "ASN-QC", "LINE-QC", "TASK-REJ", "LOC-1",
                            InboundReceiptService.LOCATION_STORAGE, new BigDecimal("5"), "CMD-TASK-REJ", "PUTAWAY-ACTOR"));
            assertEquals("QC_REJECTED", rejected.code());
            InboundException wrongLoc = assertThrows(InboundException.class,
                    () -> service.putaway("ENT-1", "WH-A", "ASN-QC", "LINE-QC", "TASK-SHIP", "LOC-SHP", "SHIPPING",
                            new BigDecimal("5"), "CMD-TASK-SHIP", "PUTAWAY-ACTOR"));
            assertEquals("INVALID_PUTAWAY_LOCATION", wrongLoc.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT putaway_physical_qty FROM inbound_line WHERE id='LINE-QC'", BigDecimal.class)
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_task WHERE document_line_id='LINE-QC'",
                Integer.class));
    }
    /** 相同事实并发换键、满额后的重试以及跨单行绑定同时覆盖。 */
    @Test
    void concurrentReceiptReplayDoesNotCountPhysicalTwiceAndCannotChangeOrderOrPayload() throws Exception {
        try (var session = sessions.openSession(false)) {
            new InboundReceiptService(session, Clock.systemUTC()).createOrder("ENT-1", "WH-A", "ASN-REPLAY", "OMS", "EXT-REPLAY", "OWNER-1",
                    List.of(Map.of("lineId", "LINE-REPLAY", "externalLineId", "L", "skuId", "SKU", "expectedQty", new BigDecimal("3"), "unit", "EA")));
            session.commit();
        }
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) {
                String key = "RCV-CONCURRENT-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try (var session = sessions.openSession(false)) {
                        var result = new InboundReceiptService(session, Clock.systemUTC()).receive("ENT-1", "WH-A", "ASN-REPLAY", "LINE-REPLAY", key, "SAME-PART", "ACTOR", new BigDecimal("3"));
                        session.commit();
                        return String.valueOf(result.get("commandId"));
                    }
                }));
            }
            start.countDown();
            String winner = results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(winner, results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
            try (var session = sessions.openSession(false)) {
                var service = new InboundReceiptService(session, Clock.systemUTC());
                assertTrue(Boolean.TRUE.equals(service.receive("ENT-1", "WH-A", "ASN-REPLAY", "LINE-REPLAY", winner, "SAME-PART", "ACTOR", new BigDecimal("3.000000")).get("replayed")));
                assertThrows(InboundException.class, () -> service.receive("ENT-1", "WH-A", "ANOTHER-ORDER", "LINE-REPLAY", winner, "SAME-PART", "ACTOR", new BigDecimal("3")));
                assertThrows(com.lrj.wms.runtime.command.CommandConflictException.class, () -> service.receive("ENT-1", "WH-A", "ASN-REPLAY", "LINE-REPLAY", winner, "SAME-PART", "ACTOR", new BigDecimal("2")));
                session.commit();
            }
        }
        assertEquals(0, new BigDecimal("3").compareTo(jdbc.queryForObject("SELECT received_physical_qty FROM inbound_line WHERE id='LINE-REPLAY'", BigDecimal.class)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id LIKE 'RCV-CONCURRENT-%'", Integer.class));
    }
}
