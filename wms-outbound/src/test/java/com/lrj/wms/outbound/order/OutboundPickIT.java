package com.lrj.wms.outbound.order;

import com.lrj.wms.outbound.protocol.SourceMapper;
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

/** S5-01：出库单/部分拣货/包裹/发运前取消。不写库存库，不派发设备。 */
class OutboundPickIT {
    private static final Instant NOW = Instant.parse("2026-09-12T06:40:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("pick", new JdbcTransactionFactory(), source));
        config.addMapper(SourceMapper.class);
        config.addMapper(OutboundOrderMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void createPickPackCancelDoesNotWriteInventoryTables() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String orderId;
        String lineId;
        String commandId;
        try (SqlSession session = sessions.openSession(false)) {
            OutboundOrderService service = new OutboundOrderService(session, clock);
            Map<String, Object> pending = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-NOAUTH", "ATT-NOAUTH",
                    "OWNER-1", " ", List.of(Map.of("orderLineId", "L1", "skuId", "SKU-1", "qty", new BigDecimal("5"),
                            "baseUnit", "EA")));
            assertEquals("PENDING_AUTHORIZATION", pending.get("status"));
            OutboundException missingAuth = assertThrows(OutboundException.class,
                    () -> service.planPickTask("ENT-1", "WH-A", String.valueOf(pending.get("id")), "L1", "LOC-1",
                            "STG-1", new BigDecimal("5")));
            assertEquals("AUTH_REQUIRED", missingAuth.code());
            Map<String, Object> created = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-1", "ATT-1", "OWNER-1",
                    "AUTH-1", List.of(Map.of("orderLineId", "L1", "skuId", "SKU-1", "qty", new BigDecimal("5"),
                            "baseUnit", "EA")));
            Map<String, Object> replay = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-1", "ATT-1", "OWNER-1",
                    "AUTH-1", List.of(Map.of("orderLineId", "L1", "skuId", "SKU-1", "qty", new BigDecimal("5"),
                            "baseUnit", "EA")));
            assertEquals(created.get("id"), replay.get("id"));
            orderId = String.valueOf(created.get("id"));
            Map<String, Object> planned = service.planPickTask("ENT-1", "WH-A", orderId, "L1", "LOC-1", "STG-1",
                    new BigDecimal("5"));
            OutboundException over = assertThrows(OutboundException.class,
                    () -> service.pickPartial("ENT-1", "WH-A", String.valueOf(planned.get("taskId")), "CMD-OVER",
                            "ACTOR", new BigDecimal("6")));
            assertEquals("OVER_PICK", over.code());
            Map<String, Object> pick = service.pickPartial("ENT-1", "WH-A", String.valueOf(planned.get("taskId")),
                    "CMD-PICK", "ACTOR", new BigDecimal("3"));
            commandId = String.valueOf(pick.get("commandId"));
            lineId = String.valueOf(pick.get("lineId"));
            Map<String, Object> applied = service.consumePick("ENT-1", "WH-A", lineId, "EVT-PICK", commandId, "APPLIED",
                    "POST-1", new BigDecimal("3"));
            assertEquals(Boolean.TRUE, applied.get("consumed"));
            Map<String, Object> replayed = service.consumePick("ENT-1", "WH-A", lineId, "EVT-PICK", commandId, "APPLIED",
                    "POST-1", new BigDecimal("3"));
            assertEquals(Boolean.FALSE, replayed.get("consumed"));
            service.pack("ENT-1", "WH-A", orderId, "L1", "PKG-1", new BigDecimal("3"));
            Map<String, Object> cancel = service.cancelUnpicked("ENT-1", "WH-A", orderId, "L1", "CMD-CXL", "ACTOR");
            assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) cancel.get("cancelledQty")));
            session.commit();
        }
        assertEquals("PACKING", jdbc.queryForObject("SELECT status FROM outbound_order WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT picked_physical_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT picked_posted_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT packed_physical_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT cancelled_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("2.000000")));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_command WHERE command_id=? AND action='PICK' AND state='APPLIED'",
                Integer.class, commandId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_command WHERE command_id='CMD-CXL' AND action='CANCEL'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_package WHERE package_no='PKG-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_task WHERE document_id=? AND task_type='RESTOCK'", Integer.class,
                orderId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name IN ('stock_balance','reservation','stock_posting')",
                Integer.class));
        System.out.println("S5_OUTBOUND: partial pick+posted replay; pack; cancel unpicked; no inventory tables");
    }

    @Test
    void shipPartialRejectsOverShipAndReplaysPosted() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String orderId;
        String lineId;
        try (SqlSession session = sessions.openSession(false)) {
            OutboundOrderService service = new OutboundOrderService(session, clock);
            Map<String, Object> created = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-SHIP", "ATT-SHIP",
                    "OWNER-1", "AUTH-2", List.of(Map.of("orderLineId", "L2", "skuId", "SKU-1", "qty",
                            new BigDecimal("5"), "baseUnit", "EA")));
            orderId = String.valueOf(created.get("id"));
            Map<String, Object> planned = service.planPickTask("ENT-1", "WH-A", orderId, "L2", "LOC-1", "STG-1",
                    new BigDecimal("5"));
            Map<String, Object> pick = service.pickPartial("ENT-1", "WH-A", String.valueOf(planned.get("taskId")),
                    "CMD-SHIP-PICK", "ACTOR", new BigDecimal("3"));
            lineId = String.valueOf(pick.get("lineId"));
            service.consumePick("ENT-1", "WH-A", lineId, "EVT-SHIP-PICK", String.valueOf(pick.get("commandId")),
                    "APPLIED", "POST-SHIP-P", new BigDecimal("3"));
            service.pack("ENT-1", "WH-A", orderId, "L2", "PKG-SHIP", new BigDecimal("3"));
            OutboundException over = assertThrows(OutboundException.class,
                    () -> service.shipPartial("ENT-1", "WH-A", orderId, "L2", "CMD-OVER-SHIP", "ACTOR",
                            new BigDecimal("4")));
            assertEquals("OVER_SHIP", over.code());
            Map<String, Object> ship = service.shipPartial("ENT-1", "WH-A", orderId, "L2", "CMD-SHIP", "ACTOR",
                    new BigDecimal("3"));
            Map<String, Object> applied = service.consumeShip("ENT-1", "WH-A", lineId, "EVT-SHIP",
                    String.valueOf(ship.get("commandId")), "APPLIED", "POST-SHIP", new BigDecimal("3"));
            assertEquals(Boolean.TRUE, applied.get("consumed"));
            Map<String, Object> replayed = service.consumeShip("ENT-1", "WH-A", lineId, "EVT-SHIP",
                    String.valueOf(ship.get("commandId")), "APPLIED", "POST-SHIP", new BigDecimal("3"));
            assertEquals(Boolean.FALSE, replayed.get("consumed"));
            service.cancelUnpicked("ENT-1", "WH-A", orderId, "L2", "CMD-SHIP-CXL", "ACTOR");
            session.commit();
        }
        assertEquals("SHIPPED", jdbc.queryForObject("SELECT status FROM outbound_order WHERE id=?", String.class,
                orderId));
        assertEquals(0, jdbc.queryForObject("SELECT shipped_posted_qty FROM outbound_line WHERE id=?", BigDecimal.class,
                lineId).compareTo(new BigDecimal("3.000000")));
        System.out.println("S5_OUTBOUND: ship bound by packed; posted replay; cancel settles SHIPPED");
    }
}
