package com.lrj.wms.inventory.inventory;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.*;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.runtime.db.DatabaseInstants;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证原订单行/尝试、并发发运和超过200分批仍可有界推进。 */
class OutboundReservationPostingIT {
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;
    private static final Clock CLOCK = Clock.systemUTC();
    @BeforeAll static void setup() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory"); mysql.start();
        var ds = new com.mysql.cj.jdbc.MysqlDataSource(); ds.setUrl(RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        ds.setUser(mysql.getUsername()); ds.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath()).load().migrate();
        var config = new Configuration(new Environment("outbound-posting-it", new JdbcTransactionFactory(), ds));
        DatabaseInstants.configure(config);
        for (var mapper : List.of(MasterdataMapper.class, InventoryMapper.class, OutboxMapper.class,
                CommandDedupMapper.class, EffectMapper.class, StockCommandMapper.class)) config.addMapper(mapper);
        sessions = new SqlSessionFactoryBuilder().build(config); jdbc = new JdbcTemplate(ds);
        try (var session = sessions.openSession(false)) {
            var md = new MasterdataService(session, CLOCK); md.createWarehouse("WH", "ENT", "WH", "隔离仓", "UTC");
            md.createLocation("SOURCE", "GATE-S", "ENT", "WH", "SOURCE", "A", "STORAGE", new BigDecimal("1000"), "EA");
            md.createLocation("STAGE", "GATE-T", "ENT", "WH", "STAGE", "A", "STAGING", new BigDecimal("1000"), "EA");
            session.commit();
        }
    }
    @AfterAll static void close() { if (mysql != null) mysql.stop(); }

    @Test void wrongIdentityUnconfirmedAndChangedReplayCannotBorrowReservation() {
        seed("IDENTITY", 8, false);
        assertFailure("RESERVATION_NOT_CONFIRMED", "IDENTITY", "PENDING-PICK", "PICK", "LINE", 1);
        try (var session = sessions.openSession(false)) {
            new InventoryApplicationService(session, CLOCK).confirmTried("ENT", "WH", "CFM-IDENTITY", "DOC", "fixture",
                    "ALLOC-IDENTITY", "ATT-IDENTITY", "xid-IDENTITY", 1L, "ReservationTccAction"); session.commit();
        }
        assertFailure("RESERVATION_LINE_INSUFFICIENT", "IDENTITY", "WRONG-LINE", "PICK", "UNKNOWN", 1);
        post("IDENTITY", "PICK-ID", "PICK", "LINE", 3);
        assertFailure("COMMAND_CONFLICT", "IDENTITY", "PICK-ID", "PICK", "OTHER", 3);
        try (var session = sessions.openSession(false)) {
            var error = assertThrows(InventoryException.class, () -> command(session, "IDENTITY", "PICK-ID", "PICK", "LINE", 3,
                    new StockPostingContext("DOC", "OWNER", "IDENTITY", "EA", "SOURCE", "STAGE", "NO_LOT", "GOOD", "OTHER-ALLOC", "ATT-IDENTITY")));
            assertEquals("COMMAND_CONFLICT", error.code()); session.rollback();
        }
        post("IDENTITY", "CANCEL-PART", "CANCEL", "LINE", 2);
        amount("SELECT remaining_qty FROM reservation_line WHERE order_line_id='OTHER' AND reservation_id=(SELECT id FROM reservation WHERE allocation_id='ALLOC-IDENTITY')", "5");
        amount("SELECT SUM(remaining_qty) FROM reservation_line WHERE order_line_id='LINE' AND parent_line_id IS NULL AND reservation_id=(SELECT id FROM reservation WHERE allocation_id='ALLOC-IDENTITY')", "3");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_command WHERE command_id IN ('PENDING-PICK','WRONG-LINE')", Integer.class));
    }

    @Test void competingShipmentsAndFinalPostingFailurePreserveAtomicity() throws Exception {
        seed("RACE", 3, true); post("RACE", "RACE-PICK", "PICK", "LINE", 3);
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { post("RACE", "RACE-SHIP-" + i, "SHIP", "LINE", 2); return true; }
                catch (InventoryException failure) { assertEquals("RESERVATION_LINE_INSUFFICIENT", failure.code()); return false; }
            })).toList(); start.countDown();
            int successes = 0; for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally { executor.shutdownNow(); }
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='RACE' AND location_id='STAGE'", "1");
        jdbc.execute("ALTER TABLE stock_posting ADD CONSTRAINT ck_it_final_posting CHECK(command_id <> 'RACE-LAST')");
        try (var session = sessions.openSession(false)) {
            assertThrows(RuntimeException.class, () -> command(session, "RACE", "RACE-LAST", "SHIP", "LINE", 1, context("RACE", "SHIP")));
            session.rollback();
        }
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='RACE' AND location_id='STAGE'", "1");
        jdbc.execute("ALTER TABLE stock_posting DROP CHECK ck_it_final_posting"); post("RACE", "RACE-LAST", "SHIP", "LINE", 1);
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='RACE' AND location_id='STAGE'", "0");
    }

    @Test void moreThanTwoHundredPickedPartsCanBeConsumedWithoutPermanentBlocking() {
        seed("PAGED", 201, true);
        try (var session = sessions.openSession(false)) {
            for (int i = 0; i < 201; i++) command(session, "PAGED", "PAGE-PICK-" + i, "PICK", "LINE", 1, context("PAGED", "PICK"));
            session.commit();
        }
        assertFailure("RESERVATION_BATCH_LIMIT", "PAGED", "PAGE-TOO-LARGE", "SHIP", "LINE", 201);
        post("PAGED", "PAGE-SHIP-1", "SHIP", "LINE", 200); post("PAGED", "PAGE-SHIP-2", "SHIP", "LINE", 1);
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='PAGED' AND location_id='STAGE'", "0");
        amount("SELECT remaining_qty FROM reservation_line WHERE order_line_id='OTHER' AND reservation_id=(SELECT id FROM reservation WHERE allocation_id='ALLOC-PAGED')", "5");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_command WHERE command_id='PAGE-TOO-LARGE'", Integer.class));
    }

    private static void seed(String sku, int qty, boolean confirmed) {
        try (var session = sessions.openSession(false)) {
            var app = new InventoryApplicationService(session, CLOCK);
            var bucket = StockBucketKey.of("ENT", "WH", "OWNER", "SOURCE", sku, "NO_LOT", "GOOD");
            app.receive("ENT", "WH", "RECEIVE-" + sku, "DOC", "fixture", bucket, Quantity.parse(Integer.toString(qty + 5), 0));
            app.reserveTried("ENT", "WH", "TRY-" + sku, "DOC", "fixture", "ALLOC-" + sku, "ATT-" + sku, "xid-" + sku,
                    1L, "ReservationTccAction", 1L, "d".repeat(64), List.of(new ReservationLineInput(bucket, Quantity.parse(Integer.toString(qty), 0), "LINE"),
                    new ReservationLineInput(bucket, Quantity.parse("5", 0), "OTHER")));
            if (confirmed) app.confirmTried("ENT", "WH", "CONFIRM-" + sku, "DOC", "fixture", "ALLOC-" + sku, "ATT-" + sku, "xid-" + sku, 1L, "ReservationTccAction");
            session.commit();
        }
    }
    private static StockPostingContext context(String sku, String action) {
        return new StockPostingContext("DOC", "OWNER", sku, "EA", action.equals("SHIP") ? "STAGE" : "SOURCE", action.equals("PICK") ? "STAGE" : null,
                "NO_LOT", "GOOD", "ALLOC-" + sku, "ATT-" + sku);
    }
    private static Map<String, Object> command(SqlSession session, String sku, String id, String action, String line, int qty, StockPostingContext context) {
        return new StockCommandService(session, CLOCK).applyOutbound("ENT", "WH", id, action, "ORDER-" + sku, id, "INTERNAL-LINE",
                "actor", "EXEC-" + id, line, context, Quantity.parse(Integer.toString(qty), 0), null);
    }
    private static void post(String sku, String id, String action, String line, int qty) {
        try (var session = sessions.openSession(false)) { command(session, sku, id, action, line, qty, context(sku, action)); session.commit(); }
    }
    private static void assertFailure(String code, String sku, String id, String action, String line, int qty) {
        try (var session = sessions.openSession(false)) {
            var failure = assertThrows(InventoryException.class, () -> command(session, sku, id, action, line, qty, context(sku, action)));
            assertEquals(code, failure.code()); session.rollback();
        }
    }
    private static void amount(String sql, String expected) { assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(sql, BigDecimal.class))); }
}
