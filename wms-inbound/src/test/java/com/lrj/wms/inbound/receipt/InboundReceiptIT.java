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
            service.consumeReceive("ENT-1", "WH-A", "LINE-1", "EVT-R1", "CMD-R1", "APPLIED", "POST-R1",
                    new BigDecimal("6"));
            service.consumeReceive("ENT-1", "WH-A", "LINE-1", "EVT-R1", "CMD-R1", "APPLIED", "POST-R1",
                    new BigDecimal("6"));
            Map<String, Object> qc = service.inspect("ENT-1", "WH-A", "INSP-1", "LINE-1", new BigDecimal("6"),
                    BigDecimal.ZERO, "QC", 1L);
            assertEquals("ACCEPTED", qc.get("resultCode"));
            Map<String, Object> putaway = service.putaway("ENT-1", "WH-A", "ASN-1", "LINE-1", "TASK-P1", "LOC-1",
                    InboundReceiptService.LOCATION_STORAGE, new BigDecimal("6"));
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
        assertEquals(0, jdbc.queryForObject(
                "SELECT putaway_physical_qty FROM inbound_line WHERE id='LINE-1'", BigDecimal.class)
                .compareTo(new BigDecimal("6.000000")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT putaway_posted_qty FROM inbound_line WHERE id='LINE-1'", BigDecimal.class)
                .compareTo(new BigDecimal("6.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_inspection WHERE id='INSP-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_task WHERE id='TASK-P1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id IN ('CMD-R1','TASK-P1')",
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
                            InboundReceiptService.LOCATION_STORAGE, new BigDecimal("5")));
            assertEquals("QC_REQUIRED", beforeQc.code());
            Map<String, Object> qc = service.inspect("ENT-1", "WH-A", "INSP-QC", "LINE-QC", BigDecimal.ZERO,
                    new BigDecimal("5"), "QC", 1L);
            assertEquals(InboundReceiptService.RESULT_REJECTED, qc.get("resultCode"));
            InboundException rejected = assertThrows(InboundException.class,
                    () -> service.putaway("ENT-1", "WH-A", "ASN-QC", "LINE-QC", "TASK-REJ", "LOC-1",
                            InboundReceiptService.LOCATION_STORAGE, new BigDecimal("5")));
            assertEquals("QC_REJECTED", rejected.code());
            InboundException wrongLoc = assertThrows(InboundException.class,
                    () -> service.putaway("ENT-1", "WH-A", "ASN-QC", "LINE-QC", "TASK-SHIP", "LOC-SHP", "SHIPPING",
                            new BigDecimal("5")));
            assertEquals("INVALID_PUTAWAY_LOCATION", wrongLoc.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT putaway_physical_qty FROM inbound_line WHERE id='LINE-QC'", BigDecimal.class)
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inbound_task WHERE document_line_id='LINE-QC'",
                Integer.class));
    }
}
