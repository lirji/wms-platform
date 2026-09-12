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

/** S4-07：活动 attempt CAS、失联隔离与空启动清理。不是真实 TC begin。 */
class FulfillmentLaunchIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:10:00Z");
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
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("launch", new JdbcTransactionFactory(), source));
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
    void leaseExpiryCannotTakeoverUntilUnknownIsolation() {
        String attemptId;
        String orderId;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, Clock.fixed(NOW, ZoneOffset.UTC));
            Map<String, Object> order = service.createOrder("ENT-LN", "OMS", "SO-LN", DIGEST, lines(), 1);
            orderId = String.valueOf(order.get("id"));
            attemptId = String.valueOf(service.createAttempt("ENT-LN", orderId, NOW.plusSeconds(120),
                    List.of("WH-A"), oneWarehouse()).get("id"));
            service.claimLaunch("ENT-LN", attemptId, "exec-1");
            FulfillmentException lost = assertThrows(FulfillmentException.class,
                    () -> service.claimLaunch("ENT-LN", attemptId, "exec-2"));
            assertEquals("LAUNCH_CAS_LOST", lost.code());
            session.commit();
        }
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService late = new FulfillmentService(session, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
            FulfillmentException lease = assertThrows(FulfillmentException.class,
                    () -> late.claimLaunch("ENT-LN", attemptId, "exec-2"));
            assertEquals("LAUNCH_CAS_LOST", lease.code());
            late.markLaunchUnknown("ENT-LN", attemptId);
            Map<String, Object> isolated = late.isolateEmptyLaunch("ENT-LN", attemptId, "recoverer");
            assertEquals("recoverer", isolated.get("launchOwner"));
            FulfillmentException oldBind = assertThrows(FulfillmentException.class,
                    () -> late.bindXid("ENT-LN", attemptId, "exec-1", "xid-old"));
            assertEquals("LAUNCH_OWNER_MISMATCH", oldBind.code());
            late.bindXid("ENT-LN", attemptId, "recoverer", "xid-rec");
            FulfillmentException reopen = assertThrows(FulfillmentException.class,
                    () -> late.createAttempt("ENT-LN", orderId, NOW.plusSeconds(180), List.of("WH-A"), oneWarehouse()));
            assertEquals("ATTEMPT_IN_PROGRESS", reopen.code());
            session.commit();
        }
        assertEquals("xid-rec", jdbc.queryForObject(
                "SELECT xid FROM allocation_attempt WHERE id=?", String.class, attemptId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_launch WHERE attempt_id=? AND launch_epoch=1 AND state='UNKNOWN'",
                Integer.class, attemptId));
        System.out.println("S4_LAUNCH: lease cannot takeover; UNKNOWN isolation fences old owner; one active attempt");
    }

    @Test
    void emptyLaunchCleanupAndBranchBlocksIsolation() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String emptyId;
        String blockedId;
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            String emptyOrder = String.valueOf(service.createOrder("ENT-CL", "OMS", "SO-CL", DIGEST, lines(), 1).get("id"));
            emptyId = String.valueOf(service.createAttempt("ENT-CL", emptyOrder, NOW.plusSeconds(120),
                    List.of("WH-A"), oneWarehouse()).get("id"));
            service.claimLaunch("ENT-CL", emptyId, "exec-1");
            Map<String, Object> recorded = service.recordKnownEmptyXid("ENT-CL", emptyId, "xid-empty");
            assertEquals(FulfillmentService.LAUNCH_UNKNOWN, recorded.get("launchState"));
            assertNull(recorded.get("attemptXid"));
            Map<String, Object> cleaned = service.cleanupEmptyLaunch("ENT-CL", emptyId);
            assertEquals(FulfillmentService.CLEANUP_CLEANED, cleaned.get("cleanupState"));
            service.isolateEmptyLaunch("ENT-CL", emptyId, "recoverer");
            service.bindXid("ENT-CL", emptyId, "recoverer", "xid-next");

            String blockedOrder = String.valueOf(service.createOrder("ENT-CL", "OMS", "SO-CL2", DIGEST, lines(), 1)
                    .get("id"));
            blockedId = String.valueOf(service.createAttempt("ENT-CL", blockedOrder, NOW.plusSeconds(120),
                    List.of("WH-A"), oneWarehouse()).get("id"));
            service.claimLaunch("ENT-CL", blockedId, "exec-1");
            service.markLaunchUnknown("ENT-CL", blockedId);
            session.commit();
        }
        jdbc.update("UPDATE allocation_participant SET reservation_id='res-blocked' WHERE attempt_id=?", blockedId);
        try (SqlSession session = sessions.openSession(false)) {
            FulfillmentService service = new FulfillmentService(session, clock);
            FulfillmentException blocked = assertThrows(FulfillmentException.class,
                    () -> service.isolateEmptyLaunch("ENT-CL", blockedId, "recoverer"));
            assertEquals("LAUNCH_NOT_ISOLATED", blocked.code());
            FulfillmentException bound = assertThrows(FulfillmentException.class,
                    () -> service.cleanupEmptyLaunch("ENT-CL", emptyId));
            assertEquals("XID_ALREADY_BOUND", bound.code());
            Map<String, Object> waiting = service.cleanupEmptyLaunch("ENT-CL", blockedId);
            assertEquals(FulfillmentService.CLEANUP_WAITING_TIMEOUT, waiting.get("cleanupState"));
            session.commit();
        }
        assertEquals("xid-next", jdbc.queryForObject(
                "SELECT xid FROM allocation_attempt WHERE id=?", String.class, emptyId));
        assertNull(jdbc.queryForObject("SELECT xid FROM allocation_attempt WHERE id=?", String.class, blockedId));
        System.out.println("S4_LAUNCH_CLEANUP: known empty cleaned; reservation blocks isolation; bound XID not replaced");
    }

    private static List<Map<String, Object>> lines() {
        return List.of(Map.of("sourceLineId", "L1", "skuId", "SKU-1", "requestedQty", new BigDecimal("2"),
                "baseUnit", "EA"));
    }

    private static List<Map<String, Object>> oneWarehouse() {
        return List.of(Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-1",
                "qty", new BigDecimal("2"), "baseUnit", "EA"));
    }
}
