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

/** S4-02：冻结参与者/数量/摘要，有界Try，显式XID传播。不是真实 TCC 二阶段。 */
class FulfillmentPlanIT {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
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
        Configuration config = new Configuration(new Environment("fulfillment", new JdbcTransactionFactory(), source));
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
    void freezeRejectsQtyMismatchAndKeepsDigest() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            String orderId = String.valueOf(service.createOrder("ENT-PLAN", "OMS", "SO-PLAN", DIGEST, lines(), 3).get("id"));
            FulfillmentException shortQty = assertThrows(FulfillmentException.class,
                    () -> service.createAttempt("ENT-PLAN", orderId, NOW.plusSeconds(60), List.of("WH-A"),
                            List.of(Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-1",
                                    "qty", new BigDecimal("1"), "baseUnit", "EA"))));
            assertEquals("QTY_MISMATCH", shortQty.code());
            Map<String, Object> attempt = service.createAttempt("ENT-PLAN", orderId, NOW.plusSeconds(60),
                    List.of("WH-B", "WH-A"), split());
            assertEquals(AllocationPlan.digest(split()), attempt.get("allocationDigest"));
            assertEquals(FulfillmentService.participantHash(new java.util.LinkedHashSet<>(List.of("WH-A", "WH-B"))),
                    attempt.get("participantSetHash"));
            FulfillmentException reopen = assertThrows(FulfillmentException.class,
                    () -> service.createAttempt("ENT-PLAN", orderId, NOW.plusSeconds(60), List.of("WH-A"),
                            List.of(Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-1",
                                    "qty", new BigDecimal("2"), "baseUnit", "EA"))));
            assertEquals("ATTEMPT_IN_PROGRESS", reopen.code());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_attempt WHERE enterprise_id='ENT-PLAN' AND allocation_digest=?",
                Integer.class, AllocationPlan.digest(split())));
    }

    @Test
    void boundedTryAndExplicitXidHeaders() {
        Clock live = Clock.fixed(NOW, ZoneOffset.UTC);
        String attemptId;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, live);
            String orderId = String.valueOf(service.createOrder("ENT-TRY", "OMS", "SO-TRY", DIGEST, lines(), 1).get("id"));
            attemptId = String.valueOf(service.createAttempt("ENT-TRY", orderId, NOW.plusSeconds(30),
                    List.of("WH-A", "WH-B"), split()).get("id"));
            FulfillmentException unbound = assertThrows(FulfillmentException.class,
                    () -> service.tryHeaders("ENT-TRY", attemptId));
            assertEquals("XID_NOT_BOUND", unbound.code());
            service.claimLaunch("ENT-TRY", attemptId, "exec-1");
            service.bindXid("ENT-TRY", attemptId, "exec-1", "xid-try-1");
            Map<String, String> headers = service.tryHeaders("ENT-TRY", attemptId);
            assertEquals("xid-try-1", headers.get(TryPropagation.XID_HEADER));
            assertEquals(TryPropagation.TM_IDENTITY, headers.get(TryPropagation.TM_HEADER));
            TryPropagation.requireMatch(headers, "xid-try-1");
            FulfillmentException mismatch = assertThrows(FulfillmentException.class,
                    () -> TryPropagation.requireMatch(Map.of(TryPropagation.XID_HEADER, "other",
                            TryPropagation.TM_HEADER, TryPropagation.TM_IDENTITY), "xid-try-1"));
            assertEquals("XID_MISMATCH", mismatch.code());
            FulfillmentException blank = assertThrows(FulfillmentException.class, () -> TryPropagation.headers(" "));
            assertEquals("XID_NOT_BOUND", blank.code());
            session.commit();
        }
        Clock expired = Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, expired);
            FulfillmentException late = assertThrows(FulfillmentException.class,
                    () -> service.tryHeaders("ENT-TRY", attemptId));
            assertEquals("TRY_DEADLINE_EXCEEDED", late.code());
            FulfillmentException claim = assertThrows(FulfillmentException.class,
                    () -> service.claimLaunch("ENT-TRY", attemptId, "exec-2"));
            assertEquals("TRY_DEADLINE_EXCEEDED", claim.code());
            session.commit();
        }
    }

    private static List<Map<String, Object>> lines() {
        return List.of(Map.of("sourceLineId", "L1", "skuId", "SKU-1", "requestedQty", new BigDecimal("2"),
                "baseUnit", "EA"));
    }

    private static List<Map<String, Object>> split() {
        return List.of(
                Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-1", "qty", new BigDecimal("1"),
                        "baseUnit", "EA"),
                Map.of("warehouseId", "WH-B", "orderLineId", "L1", "skuId", "SKU-1", "qty", new BigDecimal("1"),
                        "baseUnit", "EA"));
    }
}
