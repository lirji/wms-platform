package com.lrj.wms.fulfillment;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
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

/** S4-01：attempt/XID/participant 映射与缺证据不得 ALLOCATED。不是真实 TCC 二阶段。 */
class FulfillmentMappingIT {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final String DIGEST = "a".repeat(64);
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
        Configuration config = new Configuration(new Environment("fulfillment", new JdbcTransactionFactory(), source));
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
    void orderReplayKeepsDigestAndRejectsConflict() {
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = service(session);
            Map<String, Object> created = service.createOrder("ENT-REPLAY", "OMS", "SO-1", DIGEST, lines("L1"), 1);
            Map<String, Object> replay = service.createOrder("ENT-REPLAY", "OMS", "SO-1", DIGEST, lines("L1"), 1);
            assertEquals(created.get("id"), replay.get("id"));
            FulfillmentException conflict = assertThrows(FulfillmentException.class,
                    () -> service.createOrder("ENT-REPLAY", "OMS", "SO-1", "b".repeat(64), lines("L1"), 1));
            assertEquals("ORDER_CONFLICT", conflict.code());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_order WHERE enterprise_id='ENT-REPLAY' AND source_order_no='SO-1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fulfillment_line WHERE enterprise_id='ENT-REPLAY'", Integer.class));
    }

    @Test
    void xidBindsOnceAndMissingEvidenceCannotAllocate() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String attemptId;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            Map<String, Object> order = service.createOrder("ENT-XID", "OMS", "SO-2", DIGEST, lines("L2"), 1);
            String orderId = String.valueOf(order.get("id"));
            Map<String, Object> attempt = service.createAttempt("ENT-XID", orderId, NOW.plusSeconds(60),
                    List.of("WH-B", "WH-A"), participantLines("L2"));
            attemptId = String.valueOf(attempt.get("id"));
            assertEquals(FulfillmentService.participantHash(new java.util.LinkedHashSet<>(List.of("WH-A", "WH-B"))),
                    attempt.get("participantSetHash"));
            assertEquals(List.of("WH-A", "WH-B"), attempt.get("warehouses"));
            FulfillmentException reopen = assertThrows(FulfillmentException.class,
                    () -> service.createAttempt("ENT-XID", orderId, NOW.plusSeconds(60), List.of("WH-A", "WH-B"),
                            participantLines("L2")));
            assertEquals("ATTEMPT_IN_PROGRESS", reopen.code());
            service.claimLaunch("ENT-XID", attemptId, "exec-1");
            Map<String, Object> bound = service.bindXid("ENT-XID", attemptId, "exec-1", "xid-so2");
            assertEquals("xid-so2", bound.get("xid"));
            assertEquals(FulfillmentService.ATTEMPT_TRYING, bound.get("state"));
            Map<String, Object> replay = service.bindXid("ENT-XID", attemptId, "exec-1", "xid-so2");
            assertEquals("xid-so2", replay.get("xid"));
            FulfillmentException overwrite = assertThrows(FulfillmentException.class,
                    () -> service.bindXid("ENT-XID", attemptId, "exec-1", "xid-other"));
            assertEquals("XID_ALREADY_BOUND", overwrite.code());
            service.bindParticipant("ENT-XID", attemptId, "WH-A", "xid-so2", 11L, "reserve-A", "res-A", 1, "TRIED");
            FulfillmentException branch = assertThrows(FulfillmentException.class,
                    () -> service.bindParticipant("ENT-XID", attemptId, "WH-A", "xid-so2", 12L, "reserve-A", "res-A", 1,
                            "TRIED"));
            assertEquals("BRANCH_ALREADY_BOUND", branch.code());
            FulfillmentException missing = assertThrows(FulfillmentException.class,
                    () -> service.markAllocated("ENT-XID", attemptId));
            assertEquals("ALLOCATED_EVIDENCE_MISSING", missing.code());
            assertTrue(FulfillmentService.isRecoveryPending(missing));
            service.observeTc("ENT-XID", attemptId, FulfillmentService.TC_COMMITTED,
                    "{\"xid\":\"xid-so2\",\"status\":9}");
            FulfillmentException unconfirmed = assertThrows(FulfillmentException.class,
                    () -> service.markAllocated("ENT-XID", attemptId));
            assertEquals("PARTICIPANTS_NOT_CONFIRMED", unconfirmed.code());
            service.observeParticipant("ENT-XID", attemptId, "WH-A", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
            service.observeParticipant("ENT-XID", attemptId, "WH-B", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
            Map<String, Object> allocated = service.markAllocated("ENT-XID", attemptId);
            assertEquals(FulfillmentService.ATTEMPT_ALLOCATED, allocated.get("state"));
            session.commit();
        }
        assertEquals("xid-so2", jdbc.queryForObject(
                "SELECT xid FROM allocation_attempt WHERE id=?", String.class, attemptId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_attempt WHERE id=? AND state='ALLOCATED' AND tc_observed_status='Committed'",
                Integer.class, attemptId));
        assertEquals("xid-so2", jdbc.queryForObject(
                "SELECT xid FROM allocation_participant WHERE attempt_id=? AND warehouse_id='WH-A'",
                String.class, attemptId));
        assertNull(jdbc.queryForObject(
                "SELECT xid FROM allocation_participant WHERE attempt_id=? AND warehouse_id='WH-B'",
                String.class, attemptId));
    }

    @Test
    void duplicateXidAndConcurrentLaunchHaveSingleWinner() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String firstAttempt;
        String secondAttempt;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            String firstOrder = String.valueOf(service.createOrder("ENT-CAS", "OMS", "SO-3", DIGEST, lines("L3"), 1).get("id"));
            firstAttempt = String.valueOf(service.createAttempt("ENT-CAS", firstOrder, NOW.plusSeconds(60),
                    List.of("WH-A"), oneWarehouse("L3")).get("id"));
            service.claimLaunch("ENT-CAS", firstAttempt, "exec-1");
            service.bindXid("ENT-CAS", firstAttempt, "exec-1", "shared-xid");
            String secondOrder = String.valueOf(service.createOrder("ENT-CAS", "OMS", "SO-4", DIGEST, lines("L4"), 1).get("id"));
            secondAttempt = String.valueOf(service.createAttempt("ENT-CAS", secondOrder, NOW.plusSeconds(60),
                    List.of("WH-A"), oneWarehouse("L4")).get("id"));
            service.claimLaunch("ENT-CAS", secondAttempt, "exec-2");
            FulfillmentException duplicate = assertThrows(FulfillmentException.class,
                    () -> service.bindXid("ENT-CAS", secondAttempt, "exec-2", "shared-xid"));
            assertEquals("XID_CONFLICT", duplicate.code());
            session.commit();
        }
        String thirdOrder;
        try (SqlSession session = sessions.openSession(false)) {
            thirdOrder = String.valueOf(new FulfillmentService(session, clock)
                    .createOrder("ENT-CAS", "OMS", "SO-5", DIGEST, lines("L5"), 1).get("id"));
            session.commit();
        }
        String concurrentAttempt;
        try (SqlSession session = sessions.openSession(false)) {
            concurrentAttempt = String.valueOf(new FulfillmentService(session, clock)
                    .createAttempt("ENT-CAS", thirdOrder, NOW.plusSeconds(60), List.of("WH-A"), oneWarehouse("L5"))
                    .get("id"));
            session.commit();
        }
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        Thread a = new Thread(() -> claim(concurrentAttempt, "exec-a", clock, start, done, won, lost));
        Thread b = new Thread(() -> claim(concurrentAttempt, "exec-b", clock, start, done, won, lost));
        a.start();
        b.start();
        done.await();
        assertEquals(1, won.get());
        assertEquals(1, lost.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_attempt WHERE id=? AND launch_owner IS NOT NULL AND xid IS NULL",
                Integer.class, concurrentAttempt));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM allocation_attempt WHERE xid='shared-xid'", Integer.class));
        assertNotNull(firstAttempt);
        assertNotNull(secondAttempt);
    }

    private void claim(String attemptId, String executor, Clock clock, CyclicBarrier start, CountDownLatch done,
            AtomicInteger won, AtomicInteger lost) {
        try {
            start.await();
            try (SqlSession session = sessions.openSession(false)) {
                new FulfillmentService(session, clock).claimLaunch("ENT-CAS", attemptId, executor);
                session.commit();
                won.incrementAndGet();
            }
        } catch (FulfillmentException error) {
            assertEquals("LAUNCH_CAS_LOST", error.code());
            lost.incrementAndGet();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        } finally {
            done.countDown();
        }
    }

    private static FulfillmentService service(SqlSession session) {
        return new FulfillmentService(session, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static List<Map<String, Object>> lines(String lineId) {
        return List.of(Map.of("sourceLineId", lineId, "skuId", "SKU-1", "requestedQty", new BigDecimal("2"),
                "baseUnit", "EA"));
    }

    private static List<Map<String, Object>> oneWarehouse(String orderLineId) {
        return List.of(Map.of("warehouseId", "WH-A", "orderLineId", orderLineId, "skuId", "SKU-1",
                "qty", new BigDecimal("2"), "baseUnit", "EA"));
    }

    private static List<Map<String, Object>> participantLines(String orderLineId) {
        return List.of(
                Map.of("warehouseId", "WH-A", "orderLineId", orderLineId, "skuId", "SKU-1", "qty", new BigDecimal("1"),
                        "baseUnit", "EA"),
                Map.of("warehouseId", "WH-B", "orderLineId", orderLineId, "skuId", "SKU-1", "qty", new BigDecimal("1"),
                        "baseUnit", "EA"));
    }
}
