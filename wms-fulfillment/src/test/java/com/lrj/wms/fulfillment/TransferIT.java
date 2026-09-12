package com.lrj.wms.fulfillment;

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

/**
 * S6-01：调拨总单/仓级子单、发出接收按操作键去重、在途不超过已发出。
 * 同 JVM 履约本库，不是库存过账/HTTP，不能当作 AC-16 生产通过。
 */
class TransferIT {
    private static final Instant NOW = Instant.parse("2026-09-12T11:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("transfer", new JdbcTransactionFactory(), source));
        config.addMapper(TransferMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void issueReceiveDedupAndRejectOverReceive() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            TransferService service = new TransferService(session, clock);
            Map<String, Object> created = service.create("ENT-1", "TR-1", "WH-A", "WH-B",
                    List.of(Map.of("lineId", "TL-1", "skuId", "SKU-T", "plannedQty", new BigDecimal("5"),
                            "businessLotKey", TransferService.NO_LOT, "sourceLotId", TransferService.NO_LOT)));
            assertEquals("OPEN", created.get("status"));
            assertEquals(2, ((List<?>) created.get("legs")).size());
            Map<String, Object> firstIssue = service.issue("ENT-1", "TR-1", "TL-1", "OP-ISSUE-1", new BigDecimal("4"));
            Map<String, Object> replayIssue = service.issue("ENT-1", "TR-1", "TL-1", "OP-ISSUE-1", new BigDecimal("4"));
            assertEquals(Boolean.FALSE, firstIssue.get("replayed"));
            assertEquals(Boolean.TRUE, replayIssue.get("replayed"));
            TransferException overIssue = assertThrows(TransferException.class,
                    () -> service.issue("ENT-1", "TR-1", "TL-1", "OP-ISSUE-2", new BigDecimal("2")));
            assertEquals("OVER_ISSUE", overIssue.code());
            Map<String, Object> auth = service.authorizeReceipt("ENT-1", "TR-1", "TL-1", "COP-R1", new BigDecimal("3"));
            Map<String, Object> authReplay = service.authorizeReceipt("ENT-1", "TR-1", "TL-1", "COP-R1",
                    new BigDecimal("3"));
            assertEquals(Boolean.TRUE, authReplay.get("replayed"));
            String authId = String.valueOf(auth.get("authorizationId"));
            long tokenVersion = ((Number) auth.get("tokenVersion")).longValue();
            Map<String, Object> receive = service.receive("ENT-1", "TR-1", "TL-1", "OP-RCV-1", authId, tokenVersion,
                    new BigDecimal("3"), "LOT-T");
            Map<String, Object> replayReceive = service.receive("ENT-1", "TR-1", "TL-1", "OP-RCV-1", authId,
                    tokenVersion, new BigDecimal("3"), "LOT-T");
            assertEquals(Boolean.FALSE, receive.get("replayed"));
            assertEquals(Boolean.TRUE, replayReceive.get("replayed"));
            TransferException overQuota = assertThrows(TransferException.class,
                    () -> service.authorizeReceipt("ENT-1", "TR-1", "TL-1", "COP-R2", new BigDecimal("2")));
            assertEquals("OVER_QUOTA", overQuota.code());
            TransferException overLoss = assertThrows(TransferException.class,
                    () -> service.confirmLoss("ENT-1", "TR-1", "TL-1", "OP-LOSS-1", new BigDecimal("2")));
            assertEquals("OVER_LOSS", overLoss.code());
            Map<String, Object> cancelled = service.authorizeReceipt("ENT-1", "TR-1", "TL-1", "COP-CXL",
                    new BigDecimal("1"));
            service.cancelAuthorization("ENT-1", "TR-1", String.valueOf(cancelled.get("authorizationId")));
            service.confirmLoss("ENT-1", "TR-1", "TL-1", "OP-LOSS-OK", new BigDecimal("1"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT issued_qty FROM transfer_line WHERE id='TL-1'", BigDecimal.class)
                .compareTo(new BigDecimal("4.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT received_qty FROM transfer_line WHERE id='TL-1'", BigDecimal.class)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT loss_confirmed_qty FROM transfer_line WHERE id='TL-1'",
                BigDecimal.class).compareTo(new BigDecimal("1.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT active_receipt_quota FROM transfer_line WHERE id='TL-1'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
        assertEquals("LOT-T", jdbc.queryForObject("SELECT target_lot_id FROM transfer_line WHERE id='TL-1'",
                String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transfer_fact WHERE action='ISSUE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transfer_fact WHERE action='RECEIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transfer_fact WHERE action='LOSS'", Integer.class));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT state FROM receipt_authorization WHERE target_client_operation_id='COP-CXL'", String.class));
        assertEquals("IN_TRANSIT", jdbc.queryForObject(
                "SELECT status FROM transfer_leg WHERE warehouse_id='WH-A'", String.class));
        assertEquals("RECEIVING", jdbc.queryForObject(
                "SELECT status FROM transfer_leg WHERE warehouse_id='WH-B'", String.class));
        System.out.println("S6_TRANSFER: order/legs/in-transit; op-key dedup; over-receive rejected; not AC-16");
    }
}
