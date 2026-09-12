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

/** S4-05：TC 成功且全仓 CONFIRMED 后写 ALLOCATED 与建单/执行授权 Outbox。不是真实 TCC，也不派发设备。 */
class FulfillmentBarrierIT {
    private static final Instant NOW = Instant.parse("2026-09-12T02:00:00Z");
    private static final String DIGEST = "c".repeat(64);
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("barrier", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(FulfillmentMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void recoverCompletesAllocatedAndBackfillsMissingOutbox() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String readyId;
        String allocatedWithoutOutbox;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            readyId = readyAttempt(service, "SO-READY", "xid-ready");
            allocatedWithoutOutbox = readyAttempt(service, "SO-GAP", "xid-gap");
            service.markAllocated("ENT-BAR", allocatedWithoutOutbox);
            session.commit();
        }
        jdbc.update("DELETE FROM fulfillment_outbox WHERE attempt_id=?", allocatedWithoutOutbox);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=?", Integer.class, allocatedWithoutOutbox));
        assertEquals("ALLOCATED", jdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, allocatedWithoutOutbox));
        assertEquals("TCC_TRYING", jdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, readyId));

        try (SqlSession session = sessions.openSession(false)) {
            int recovered = new FulfillmentService(session, clock).recoverReadyBarriers("ENT-BAR");
            assertEquals(2, recovered);
            session.commit();
        }
        assertEquals("ALLOCATED", jdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, readyId));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND status='PENDING'",
                Integer.class, readyId));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND status='PENDING'",
                Integer.class, allocatedWithoutOutbox));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND event_type='OutboundOrderRequested' "
                        + "AND warehouse_id='WH-A'",
                Integer.class, readyId));
        assertEquals("res-A", jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.reservationId')) FROM fulfillment_outbox "
                        + "WHERE attempt_id=? AND event_type='ExecutionAuthorizationRequested' AND warehouse_id='WH-A'",
                String.class, readyId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name IN ('source_command','source_execution','wcs_command')",
                Integer.class));
        System.out.println("S4_ALLOCATED_BARRIER: ALLOCATED+outbound/execution Outbox in local TX; "
                + "recovery backfills missing events; no device dispatch");
    }

    private static String readyAttempt(FulfillmentService service, String sourceOrderNo, String xid) {
        Map<String, Object> order = service.createOrder("ENT-BAR", "OMS", sourceOrderNo, DIGEST,
                List.of(Map.of("sourceLineId", "L1", "skuId", "SKU-1", "requestedQty", new BigDecimal("2"),
                        "baseUnit", "EA")), 1);
        String orderId = String.valueOf(order.get("id"));
        String attemptId = String.valueOf(service.createAttempt("ENT-BAR", orderId, NOW.plusSeconds(60),
                List.of("WH-A", "WH-B"), List.of(
                        Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-1",
                                "qty", new BigDecimal("1"), "baseUnit", "EA"),
                        Map.of("warehouseId", "WH-B", "orderLineId", "L1", "skuId", "SKU-1",
                                "qty", new BigDecimal("1"), "baseUnit", "EA"))).get("id"));
        service.claimLaunch("ENT-BAR", attemptId, "exec-1");
        service.bindXid("ENT-BAR", attemptId, "exec-1", xid);
        service.bindParticipant("ENT-BAR", attemptId, "WH-A", xid, 11L, "ReservationTccAction", "res-A", 1, "TRIED");
        service.observeTc("ENT-BAR", attemptId, FulfillmentService.TC_COMMITTED,
                "{\"xid\":\"" + xid + "\",\"status\":9}");
        service.observeParticipant("ENT-BAR", attemptId, "WH-A", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
        service.observeParticipant("ENT-BAR", attemptId, "WH-B", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
        return attemptId;
    }
}
