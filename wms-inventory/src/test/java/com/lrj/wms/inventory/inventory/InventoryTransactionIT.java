package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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

/** V004 余额/预占/流水约束与 Mapper 条件更新；不是并发过账原语。 */
class InventoryTransactionIT {
    private static final Instant NOW = Instant.parse("2026-09-10T13:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
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
    void emptyBucketUniqueKeyAndReserveGood() {
        Timestamp now = Timestamp.from(NOW);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryMapper mapper = session.getMapper(InventoryMapper.class);
            Map<String, Object> gate = mapper.lockGate("ENT-1", "WH-A", "LOC-1");
            assertEquals(MasterdataCodes.GATE_OPEN, gate.get("state"));
            assertEquals(1, mapper.insertBalance("BAL-1", "ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-1",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD, now));
            assertEquals(1, mapper.insertBalance("BAL-OTHER", "ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-1",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD, now));
            Map<String, Object> locked = mapper.lockBalanceByDimension("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-1",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            assertEquals("BAL-1", locked.get("id"));
            assertEquals(1, mapper.casAdjust("ENT-1", "WH-A", "BAL-1", new BigDecimal("10"), BigDecimal.ZERO,
                    BigDecimal.ZERO, 0L, now));
            assertEquals(1, mapper.casReserveGood("ENT-1", "WH-A", "BAL-1", new BigDecimal("4"), 1L, now));
            assertEquals(0, mapper.casReserveGood("ENT-1", "WH-A", "BAL-1", new BigDecimal("7"), 2L, now));
            Map<String, Object> after = mapper.lockBalanceById("ENT-1", "WH-A", "BAL-1");
            assertEquals(0, new BigDecimal(after.get("on_hand_qty").toString()).compareTo(new BigDecimal("10.000000")));
            assertEquals(0, new BigDecimal(after.get("reserved_qty").toString()).compareTo(new BigDecimal("4.000000")));
            session.commit();
        }
    }

    @Test
    void holdBucketCannotUseGoodReservePathAndCheckRejectsOverOccupy() {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO stock_balance (id, enterprise_id, warehouse_id, owner_id, location_id, sku_id, lot_id, "
                + "quality_code, on_hand_qty, reserved_qty, free_execution_claim_qty, created_at, updated_at) VALUES "
                + "('BAL-HOLD', 'ENT-1', 'WH-A', 'OWNER-1', 'LOC-1', 'SKU-HOLD', 'NO_LOT', 'HOLD', 5, 0, 0, ?, ?)", now, now);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryMapper mapper = session.getMapper(InventoryMapper.class);
            assertEquals(0, mapper.casReserveGood("ENT-1", "WH-A", "BAL-HOLD", BigDecimal.ONE, 0L, now));
            session.rollback();
        }
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE stock_balance SET reserved_qty=6 WHERE id='BAL-HOLD'"));
    }

    @Test
    void ledgerAndReservationUniqueness() {
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("INSERT INTO stock_balance (id, enterprise_id, warehouse_id, owner_id, location_id, sku_id, lot_id, "
                + "quality_code, on_hand_qty, created_at, updated_at) VALUES "
                + "('BAL-LED', 'ENT-1', 'WH-A', 'OWNER-1', 'LOC-1', 'SKU-LED', 'NO_LOT', 'GOOD', 8, ?, ?)", now, now);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryMapper mapper = session.getMapper(InventoryMapper.class);
            assertEquals(1, mapper.insertLedger("LED-1", "ENT-1", "WH-A", "OP-1", 1, "BAL-LED", new BigDecimal("8"),
                    BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("8"), BigDecimal.ZERO, BigDecimal.ZERO, 1L, "RECEIVE",
                    "DOC-1", "ACTOR-1", now, now));
            assertEquals(1, mapper.insertReservation("RSV-1", "ENT-1", "WH-A", "ALLOC-1", "ATT-1", DIGEST, 1,
                    ReservationState.TRIED, "xid-1", 11L, "ReservationTccAction", 1L, null, now));
            assertEquals(1, mapper.insertReservationLine("LINE-1", "ENT-1", "WH-A", "RSV-1", null, "OL-1", "BAL-LED",
                    new BigDecimal("3"), new BigDecimal("3"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, now));
            session.commit();
        }
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO stock_ledger (id, enterprise_id, warehouse_id, operation_id, entry_no, balance_id, on_hand_delta, "
                        + "reserved_delta, free_execution_claim_delta, on_hand_after, reserved_after, free_execution_claim_after, "
                        + "balance_version, reason_code, document_id, actor_id, occurred_at, created_at) VALUES "
                        + "('LED-2', 'ENT-1', 'WH-A', 'OP-2', 1, 'BAL-LED', 0, 0, 0, 8, 0, 0, 1, 'RECEIVE', 'DOC-2', 'A', ?, ?)",
                now, now));
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO reservation (id, enterprise_id, warehouse_id, allocation_id, attempt_id, request_digest, "
                        + "digest_version, state, xid, branch_id, action_name, route_epoch, created_at, updated_at) VALUES "
                        + "('RSV-2', 'ENT-1', 'WH-A', 'ALLOC-2', 'ATT-2', ?, 1, 'TRIED', 'xid-1', 11, 'ReservationTccAction', 1, ?, ?)",
                DIGEST, now, now));
        assertThrows(Exception.class, () -> jdbc.update(
                "INSERT INTO reservation_line (id, enterprise_id, warehouse_id, reservation_id, order_line_id, balance_id, "
                        + "requested_qty, remaining_qty, picked_qty, consumed_qty, released_qty, inflight_qty, created_at, updated_at) "
                        + "VALUES ('LINE-BAD', 'ENT-1', 'WH-A', 'RSV-1', 'OL-2', 'BAL-LED', 5, 3, 0, 0, 0, 0, ?, ?)",
                now, now));
    }
}
