package com.lrj.wms.fulfillment;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/** S4-06：恢复扫描同步观察并补齐 Outbox。不是真实 TC 查询、XXL admin 或二阶段。 */
class AllocationRecoverySweepIT {
    private static final Instant NOW = Instant.parse("2026-09-12T04:10:00Z");
    private static final String DIGEST = "d".repeat(64);
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
        Configuration config = new Configuration(new Environment("sweep", new JdbcTransactionFactory(), source));
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
    void unavailablePortKeepsTriedAndStubPortBackfillsOutbox() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String pendingId;
        String readyId;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            pendingId = readyAttempt(service, "SO-PEND", "xid-pend", false);
            readyId = readyAttempt(service, "SO-SYNC", "xid-sync", true);
            assertEquals(0, new AllocationRecoveryJob(
                    new AllocationRecoverySweep(service, new UnavailableTcStatusPort()), "ENT-SW").execute()
                    .newlyObserved());
            session.commit();
        }
        assertEquals("TCC_TRYING", jdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, pendingId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=?", Integer.class, pendingId));

        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            TcStatusPort port = xid -> "xid-pend".equals(xid)
                    ? Optional.of(new TcStatusPort.Observation(FulfillmentService.TC_COMMITTED,
                            "{\"xid\":\"xid-pend\",\"status\":9}"))
                    : Optional.empty();
            AllocationRecoverySweep.Report report = new AllocationRecoveryJob(
                    new AllocationRecoverySweep(service, port), "ENT-SW").execute();
            assertTrue(report.newlyObserved() >= 1);
            assertTrue(report.recovered() >= 2);
            assertThrows(IllegalStateException.class, AllocationRecoverySweep::refusePhaseTwo);
            session.commit();
        }
        assertEquals("ALLOCATED", jdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, pendingId));
        assertEquals("ALLOCATED", jdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, readyId));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND status='PENDING'",
                Integer.class, pendingId));
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND status='PENDING'",
                Integer.class, readyId));
        System.out.println("S4_XXL_SWEEP: unavailable port leaves pending; stub observation backfills Outbox; "
                + "no Confirm/Cancel");
    }

    private static String readyAttempt(FulfillmentService service, String sourceOrderNo, String xid,
            boolean observeCommitted) {
        Map<String, Object> order = service.createOrder("ENT-SW", "OMS", sourceOrderNo, DIGEST,
                List.of(Map.of("sourceLineId", "L1", "skuId", "SKU-1", "requestedQty", new BigDecimal("2"),
                        "baseUnit", "EA")), 1);
        String attemptId = String.valueOf(service.createAttempt("ENT-SW", String.valueOf(order.get("id")),
                NOW.plusSeconds(60), List.of("WH-A", "WH-B"), List.of(
                        Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-1",
                                "qty", new BigDecimal("1"), "baseUnit", "EA"),
                        Map.of("warehouseId", "WH-B", "orderLineId", "L1", "skuId", "SKU-1",
                                "qty", new BigDecimal("1"), "baseUnit", "EA"))).get("id"));
        service.claimLaunch("ENT-SW", attemptId, "exec-1");
        service.bindXid("ENT-SW", attemptId, "exec-1", xid);
        service.bindParticipant("ENT-SW", attemptId, "WH-A", xid, 11L, "ReservationTccAction", "res-A", 1, "TRIED");
        service.observeParticipant("ENT-SW", attemptId, "WH-A", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
        service.observeParticipant("ENT-SW", attemptId, "WH-B", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
        if (observeCommitted) {
            service.observeTc("ENT-SW", attemptId, FulfillmentService.TC_COMMITTED,
                    "{\"xid\":\"" + xid + "\",\"status\":9}");
        }
        return attemptId;
    }
}
