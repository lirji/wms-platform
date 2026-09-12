package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
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

/** S2-03/S2-04 收货原语、同键重放与同键异内容拒绝。不是并发 100 件 AC-03。 */
class InventoryApplicationIT {
    private static final Instant NOW = Instant.parse("2026-09-10T13:00:00Z");
    private static final String DIGEST = "b".repeat(64);

    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper.class);
        config.addMapper(com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-2", "GATE-2", "ENT-1", "WH-A", "A-02", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void receiveReserveCancelAndIdempotentReplay() {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-1", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Quantity ten = Quantity.parse("10", 0);
        Quantity four = Quantity.parse("4", 0);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService service = new InventoryApplicationService(session, clock);
            assertEquals("OP-RCV", service.receive("ENT-1", "WH-A", "OP-RCV", "DOC-1", "ACTOR", bucket, ten));
            assertEquals("OP-RCV", service.receive("ENT-1", "WH-A", "OP-RCV", "DOC-1", "ACTOR", bucket, ten));
            String reservationId = service.reserve("ENT-1", "WH-A", "OP-RSV", "DOC-2", "ACTOR", "ALLOC-1", "ATT-1",
                    "xid-1", 7L, "ReservationTccAction", 1L, DIGEST, bucket, four, "OL-1");
            assertNotNull(reservationId);
            InventoryException insufficient = assertThrows(InventoryException.class,
                    () -> service.reserve("ENT-1", "WH-A", "OP-RSV-2", "DOC-3", "ACTOR", "ALLOC-2", "ATT-2", "xid-2", 8L,
                            "ReservationTccAction", 1L, DIGEST, bucket, Quantity.parse("7", 0), "OL-2"));
            assertEquals("STOCK_INSUFFICIENT", insufficient.code());
            service.cancelTried("ENT-1", "WH-A", "OP-CXL", "DOC-4", "ACTOR", "ALLOC-1", "ATT-1");
            service.cancelTried("ENT-1", "WH-A", "OP-CXL", "DOC-4", "ACTOR", "ALLOC-1", "ATT-1");
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-1'", BigDecimal.class)
                .compareTo(new BigDecimal("0.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-1'", BigDecimal.class)
                .compareTo(new BigDecimal("10.000000")));
        assertEquals(ReservationState.CANCELLED,
                jdbc.queryForObject("SELECT state FROM reservation WHERE allocation_id='ALLOC-1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-RCV'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-CXL'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-RCV' AND status='PENDING'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-CXL'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-RSV-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_dedup WHERE client_operation_id='OP-RCV' AND action='RECEIVE'", Integer.class));
        InventoryException conflict = assertThrows(InventoryException.class, () -> {
            try (SqlSession session = sessions.openSession(false)) {
                InventoryApplicationService service = new InventoryApplicationService(session,
                        Clock.fixed(NOW, ZoneOffset.UTC));
                service.receive("ENT-1", "WH-A", "OP-RCV", "DOC-OTHER", "ACTOR", bucket, ten);
            }
        });
        assertEquals("COMMAND_CONFLICT", conflict.code());
    }

    @Test
    void frozenGateRejectsReceive() {
        jdbc.update("UPDATE location_gate SET state='FROZEN' WHERE location_id='LOC-1'");
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-FZ", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService service = new InventoryApplicationService(session,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            InventoryException error = assertThrows(InventoryException.class,
                    () -> service.receive("ENT-1", "WH-A", "OP-FZ", "DOC", "ACTOR", bucket, Quantity.parse("1", 0)));
            assertEquals("STOCK_FROZEN", error.code());
            session.rollback();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-FZ'", Integer.class));
        jdbc.update("UPDATE location_gate SET state='OPEN' WHERE location_id='LOC-1'");
    }

    @Test
    void moveReservedThenShipAndRejectOverShip() {
        StockBucketKey storage = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-MV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        StockBucketKey stage = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-2", "SKU-MV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Quantity ten = Quantity.parse("10", 0);
        Quantity four = Quantity.parse("4", 0);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService service = new InventoryApplicationService(session, clock);
            service.receive("ENT-1", "WH-A", "OP-MV-RCV", "DOC", "ACTOR", storage, ten);
            service.reserve("ENT-1", "WH-A", "OP-MV-RSV", "DOC", "ACTOR", "ALLOC-MV", "ATT-MV", "xid-mv", 3L,
                    "ReservationTccAction", 1L, DIGEST, storage, four, "OL-MV");
            service.move("ENT-1", "WH-A", "OP-MV", "DOC", "ACTOR", storage, stage, four, true);
            service.move("ENT-1", "WH-A", "OP-MV", "DOC", "ACTOR", storage, stage, four, true);
            service.ship("ENT-1", "WH-A", "OP-SHIP", "DOC", "ACTOR", stage, four);
            InventoryException over = assertThrows(InventoryException.class,
                    () -> service.ship("ENT-1", "WH-A", "OP-SHIP-2", "DOC", "ACTOR", stage, Quantity.parse("1", 0)));
            assertEquals("STOCK_INSUFFICIENT", over.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-MV' AND location_id='LOC-1'",
                BigDecimal.class).compareTo(new BigDecimal("6.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-MV' AND location_id='LOC-1'",
                BigDecimal.class).compareTo(new BigDecimal("0.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-MV' AND location_id='LOC-2'",
                BigDecimal.class).compareTo(new BigDecimal("0.000000")));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-MV'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-SHIP'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-MV' AND status='PENDING'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-SHIP'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-SHIP-2'", Integer.class));
    }
}
