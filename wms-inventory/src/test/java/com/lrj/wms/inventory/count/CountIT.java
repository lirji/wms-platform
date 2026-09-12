package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

/** S6-03：排空、冻结快照、点数复盘、审批调整与预占冲突。不是 AC-18/19 生产。 */
class CountIT {
    private static final Instant NOW = Instant.parse("2026-09-12T03:00:00Z");
    private static final String DIGEST = "d".repeat(64);
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
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(CountMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class);
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
    void quiesceFreezeObserveApproveAndReservationConflict() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey good = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-C", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            CountService counts = new CountService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-C-RCV", "DOC-C", "ACTOR", good, Quantity.parse("10", 0));
            assertEquals(CountService.DRAFT,
                    counts.create("ENT-1", "WH-A", "CP-1", "CYCLE", List.of("LOC-1")).get("status"));
            assertEquals(CountService.QUIESCING, counts.startQuiescing("ENT-1", "WH-A", "CP-1").get("status"));
            InventoryException denied = assertThrows(InventoryException.class,
                    () -> inventory.reserve("ENT-1", "WH-A", "OP-C-RSV", "DOC-C", "ACTOR", "ALLOC-C", "ATT-C", "xid-c",
                            1L, "ReservationTccAction", 1L, DIGEST, good, Quantity.parse("1", 0), "OL-C"));
            assertEquals("STOCK_FROZEN", denied.code());
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-1");
            assertEquals(CountService.FROZEN, frozen.get("status"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            assertEquals(1, lines.size());
            String lineId = String.valueOf(lines.getFirst().get("id"));
            assertEquals(0, new BigDecimal(String.valueOf(lines.getFirst().get("snapshotQty")))
                    .compareTo(new BigDecimal("10.000000")));
            assertEquals("8.000000", counts.observe("ENT-1", "WH-A", "CP-1", lineId, "OBS-1", "8", "ACTOR", 1)
                    .get("qty").toString());
            assertEquals("7.000000", counts.observe("ENT-1", "WH-A", "CP-1", lineId, "OBS-2", "7", "ACTOR", 2)
                    .get("qty").toString());
            assertEquals("8.000000", counts.observe("ENT-1", "WH-A", "CP-1", lineId, "OBS-1", "8", "ACTOR", 1)
                    .get("qty").toString());
            assertEquals(2, session.getMapper(CountMapper.class).countObservations("ENT-1", "WH-A", lineId));
            assertEquals(CountService.REVIEWING, counts.submitReview("ENT-1", "WH-A", "CP-1").get("status"));
            assertEquals(CountService.APPROVED, counts.approve("ENT-1", "WH-A", "CP-1", "AP-1", "APPR").get("status"));
            assertEquals(CountService.LINE_APPLIED,
                    counts.applyLine("ENT-1", "WH-A", "CP-1", lineId, "OP-C-ADJ", "ACTOR").get("status"));
            assertEquals(CountService.COMPLETED, counts.unfreeze("ENT-1", "WH-A", "CP-1").get("status"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-C'",
                BigDecimal.class).compareTo(new BigDecimal("7.000000")));
        assertEquals("OPEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-1'",
                String.class));
        assertNull(jdbc.queryForObject("SELECT count_plan_id FROM location_gate WHERE location_id='LOC-1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-C-ADJ'",
                Integer.class));

        StockBucketKey conflict = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-CF", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            CountService counts = new CountService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-CF-RCV", "DOC-CF", "ACTOR", conflict, Quantity.parse("10", 0));
            inventory.reserve("ENT-1", "WH-A", "OP-CF-RSV", "DOC-CF", "ACTOR", "ALLOC-CF", "ATT-CF", "xid-cf", 2L,
                    "ReservationTccAction", 1L, DIGEST, conflict, Quantity.parse("6", 0), "OL-CF");
            counts.create("ENT-1", "WH-A", "CP-2", "CYCLE", List.of("LOC-1"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-2");
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-2");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            String conflictLine = null;
            int obs = 1;
            InventoryMapper balances = session.getMapper(InventoryMapper.class);
            for (Map<String, Object> line : lines) {
                String sku = String.valueOf(balances.lockBalanceById("ENT-1", "WH-A", String.valueOf(line.get("balanceId")))
                        .get("sku_id"));
                String counted = "SKU-CF".equals(sku) ? "3" : "7";
                counts.observe("ENT-1", "WH-A", "CP-2", String.valueOf(line.get("id")), "OBS-CF-" + obs, counted, "ACTOR",
                        1);
                if ("SKU-CF".equals(sku)) {
                    conflictLine = String.valueOf(line.get("id"));
                }
                obs++;
            }
            assertNotNull(conflictLine);
            String applyConflictLine = conflictLine;
            counts.submitReview("ENT-1", "WH-A", "CP-2");
            counts.approve("ENT-1", "WH-A", "CP-2", "AP-2", "APPR");
            InventoryException conflicted = assertThrows(InventoryException.class,
                    () -> counts.applyLine("ENT-1", "WH-A", "CP-2", applyConflictLine, "OP-CF-ADJ", "ACTOR"));
            assertEquals("RESERVATION_CONFLICT", conflicted.code());
            InventoryException pending = assertThrows(InventoryException.class,
                    () -> counts.unfreeze("ENT-1", "WH-A", "CP-2"));
            assertEquals("COUNT_APPLY_PENDING", pending.code());
            session.commit();
        }
        assertEquals("RESERVATION_CONFLICT", jdbc.queryForObject(
                "SELECT status FROM count_line WHERE count_plan_id='CP-2' AND balance_id="
                        + "(SELECT id FROM stock_balance WHERE sku_id='SKU-CF')", String.class));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-1'",
                String.class));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-CF'",
                BigDecimal.class).compareTo(new BigDecimal("10.000000")));

        StockBucketKey drain = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-2", "SKU-DR", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            CountService counts = new CountService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-DR-RCV", "DOC-DR", "ACTOR", drain, Quantity.parse("5", 0));
            session.commit();
        }
        jdbc.update("UPDATE stock_balance SET free_execution_claim_qty=1 WHERE sku_id='SKU-DR'");
        try (SqlSession session = sessions.openSession(false)) {
            CountService counts = new CountService(session, clock);
            counts.create("ENT-1", "WH-A", "CP-3", "CYCLE", List.of("LOC-2"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-3");
            InventoryException draining = assertThrows(InventoryException.class,
                    () -> counts.freeze("ENT-1", "WH-A", "CP-3"));
            assertEquals("COUNT_DRAIN_PENDING", draining.code());
            session.commit();
        }
        assertEquals("QUIESCING", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-2'",
                String.class));
    }
}
