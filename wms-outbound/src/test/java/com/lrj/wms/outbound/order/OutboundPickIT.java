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
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("pick", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(SourceMapper.class);
        config.addMapper(OutboundOrderMapper.class);
        config.addMapper(OutboundSerialMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.SourceContextMapper.class);
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
            authorizeForTest(session, "ENT-1", "WH-A", String.valueOf(created.get("id")), "ATT-1", "AUTH-1");
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
        assertEquals(0, jdbc.queryForObject(
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
            authorizeForTest(session, "ENT-1", "WH-A", String.valueOf(created.get("id")), "ATT-SHIP", "AUTH-2");
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

    /** 多次部分执行各有独立事实，同分批换键和满额后重试都不能再增加实物量。 */
    @Test
    void partialExecutionsUseSeparateFactsAndReplaysKeepExactTotals() {
        try (var session = sessions.openSession(false)) {
            var service = new OutboundOrderService(session, Clock.systemUTC());
            var created = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-PARTS", "ATT-PARTS", "OWNER", null,
                    List.of(Map.of("orderLineId", "LP", "skuId", "SKU", "qty", new BigDecimal("5"), "baseUnit", "EA")));
            String order = String.valueOf(created.get("id"));
            authorizeForTest(session, "ENT-1", "WH-A", order, "ATT-PARTS", "AUTH-PARTS");
            String task = String.valueOf(service.planPickTask("ENT-1", "WH-A", order, "LP", "LOC", "STAGE", new BigDecimal("5")).get("taskId"));
            var first = service.pickPartial("ENT-1", "WH-A", task, "PARTS-P1", "ACTOR", new BigDecimal("3"), "P1");
            var second = service.pickPartial("ENT-1", "WH-A", task, "PARTS-P2", "ACTOR", new BigDecimal("2"));
            assertNotEquals(first.get("effectId"), second.get("effectId"));
            assertEquals(first.get("commandId"), service.pickPartial("ENT-1", "WH-A", task, "PARTS-P1-RETRY", "ACTOR", new BigDecimal("3.00"), "P1").get("commandId"));
            assertThrows(com.lrj.wms.runtime.command.CommandConflictException.class,
                    () -> service.pickPartial("ENT-1", "WH-A", task, "PARTS-P1", "ACTOR", new BigDecimal("1"), "P1"));
            service.pack("ENT-1", "WH-A", order, "LP", "PACK-PARTS", new BigDecimal("5"));
            var ship1 = service.shipPartial("ENT-1", "WH-A", order, "LP", "PARTS-S1", "ACTOR", new BigDecimal("3"));
            var ship2 = service.shipPartial("ENT-1", "WH-A", order, "LP", "PARTS-S2", "ACTOR", new BigDecimal("2"));
            assertNotEquals(ship1.get("effectId"), ship2.get("effectId"));
            assertEquals(ship1.get("commandId"), service.shipPartial("ENT-1", "WH-A", order, "LP", "PARTS-S1", "ACTOR", new BigDecimal("3")).get("commandId"));
            var line = session.getMapper(OutboundOrderMapper.class).lockLineByOrderLine("ENT-1", "WH-A", order, "LP");
            assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) line.get("picked_physical_qty")));
            assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) line.get("shipped_physical_qty")));
            session.commit();
        }
    }

    /** 只完成或取消第一行时，头状态必须继续允许剩余行作业。 */
    @Test
    void headerOnlyBecomesTerminalAfterEveryLineIsSettled() {
        try (var session = sessions.openSession(false)) {
            var service = new OutboundOrderService(session, Clock.systemUTC());
            var created = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-MULTI", "ATT-MULTI", "OWNER", null,
                    List.of(Map.of("orderLineId", "M1", "skuId", "SKU", "qty", new BigDecimal("2"), "baseUnit", "EA"),
                            Map.of("orderLineId", "M2", "skuId", "SKU", "qty", new BigDecimal("2"), "baseUnit", "EA")));
            String order = String.valueOf(created.get("id"));
            authorizeForTest(session, "ENT-1", "WH-A", order, "ATT-MULTI", "AUTH-MULTI");
            service.cancelUnpicked("ENT-1", "WH-A", order, "M1", "CANCEL-M1", "ACTOR");
            assertTrue(Boolean.TRUE.equals(service.cancelUnpicked("ENT-1", "WH-A", order, "M1", "CANCEL-M1", "ACTOR").get("replayed")));
            assertNotEquals("CANCELLED", service.getOrder("ENT-1", "WH-A", order).get("status"));
            String task = String.valueOf(service.planPickTask("ENT-1", "WH-A", order, "M2", "LOC", "STAGE", new BigDecimal("2")).get("taskId"));
            service.pickPartial("ENT-1", "WH-A", task, "PICK-M2", "ACTOR", new BigDecimal("2"));
            service.pack("ENT-1", "WH-A", order, "M2", "PACK-M2", new BigDecimal("2"));
            service.shipPartial("ENT-1", "WH-A", order, "M2", "SHIP-M2", "ACTOR", new BigDecimal("2"));
            assertEquals("SHIPPED", service.getOrder("ENT-1", "WH-A", order).get("status"));
            session.commit();
        }
    }

    /** 规划重放复用任务；未拣任务占用规划额度，部分完成不会释放已经分给该任务的剩余量。 */
    @Test
    void planningIsIdempotentAndAccountsForOutstandingTasks() {
        try (var session = sessions.openSession(false)) {
            var service = new OutboundOrderService(session, Clock.systemUTC());
            var created = service.createFromAllocation("ENT-1", "WH-A", "ALLOC-PLAN", "ATT-PLAN", "OWNER", null,
                    List.of(Map.of("orderLineId", "PL", "skuId", "SKU", "qty", new BigDecimal("5"), "baseUnit", "EA")));
            String order = String.valueOf(created.get("id"));
            authorizeForTest(session, "ENT-1", "WH-A", order, "ATT-PLAN", "AUTH-PLAN");
            var first = service.planPickTask("ENT-1", "WH-A", order, "PL", "LOC", "STAGE", new BigDecimal("3"), "PLAN-1");
            assertEquals(first.get("taskId"), service.planPickTask("ENT-1", "WH-A", order, "PL", "LOC", "STAGE", new BigDecimal("3.0"), "PLAN-1").get("taskId"));
            assertThrows(com.lrj.wms.runtime.command.CommandConflictException.class,
                    () -> service.planPickTask("ENT-1", "WH-A", order, "PL", "OTHER", "STAGE", new BigDecimal("3"), "PLAN-1"));
            assertThrows(OutboundException.class, () -> service.planPickTask("ENT-1", "WH-A", order, "PL", "LOC", "STAGE", new BigDecimal("3"), "PLAN-OVER"));
            service.pickPartial("ENT-1", "WH-A", String.valueOf(first.get("taskId")), "PICK-PLAN", "ACTOR", BigDecimal.ONE);
            service.planPickTask("ENT-1", "WH-A", order, "PL", "LOC", "STAGE", new BigDecimal("2"), "PLAN-2");
            assertThrows(OutboundException.class, () -> service.planPickTask("ENT-1", "WH-A", order, "PL", "LOC", "STAGE", BigDecimal.ONE, "PLAN-EXTRA"));
            assertEquals(2, session.getMapper(OutboundOrderMapper.class).listTasks("ENT-1", "WH-A", order).size());
            session.commit();
        }
    }

    /** 仅本地测试的终态证据夹具；仍调用实际授权服务，不证明真实 TC 集成。 */
    @Test void serialSelectionIsImmutableExclusiveAndPostedAtomically() {
        var clock=Clock.fixed(NOW,ZoneOffset.UTC);String task,other,line;
        var selected=selection("SN-1","SN-2");
        try(var session=sessions.openSession(false)) {
            var service=new OutboundOrderService(session,clock);
            var created=service.createFromAllocation("ENT-1","WH-A","ALLOC-SERIAL","ATT-SERIAL","OWNER",null,List.of(Map.of("orderLineId","SERIAL-LINE","skuId","SERIAL-SKU","qty",new BigDecimal("5"),"baseUnit","EA")));
            String order=created.get("id").toString();authorizeForTest(session,"ENT-1","WH-A",order,"ATT-SERIAL","AUTH-SERIAL");
            task=service.planPickTask("ENT-1","WH-A",order,"SERIAL-LINE","LOC","STAGE",new BigDecimal("3"),"SERIAL-TASK").get("taskId").toString();
            other=service.planPickTask("ENT-1","WH-A",order,"SERIAL-LINE","LOC","STAGE",new BigDecimal("2"),"SERIAL-TASK-OTHER").get("taskId").toString();
            var result=new OutboundPostingService(session,clock).pick("ENT-1","WH-A",task,"SERIAL-P1","actor",new BigDecimal("2"),"SERIAL-PART","NO_LOT",selected);
            line=result.get("lineId").toString();session.commit();
        }
        try(var session=sessions.openSession(false)) {
            var posting=new OutboundPostingService(session,clock);
            assertEquals("SERIAL-P1",posting.pick("ENT-1","WH-A",task,"SERIAL-P1","actor",new BigDecimal("2.00"),"SERIAL-PART","NO_LOT",selection("sn-2","sn-1")).get("commandId"));session.commit();
        }
        try(var session=sessions.openSession(false)) {
            assertThrows(com.lrj.wms.runtime.command.CommandConflictException.class,() -> new OutboundPostingService(session,clock).pick("ENT-1","WH-A",task,"SERIAL-P1","actor",new BigDecimal("2"),"SERIAL-PART","NO_LOT",selection("SN-1","SN-3")));session.rollback();
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("SERIAL_PICK_CONFLICT",assertThrows(OutboundException.class,() -> new OutboundPostingService(session,clock).pick("ENT-1","WH-A",other,"SERIAL-DUP","actor",BigDecimal.ONE,"SERIAL-DUP-PART","NO_LOT",selection("SN-2"))).code());session.rollback();
        }
        assertEquals(0,jdbc.queryForObject("SELECT completed_qty FROM outbound_task WHERE id=?",BigDecimal.class,other).signum());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='SERIAL-DUP'",Integer.class));
        jdbc.execute("ALTER TABLE outbound_serial_pick ADD CONSTRAINT fail_serial_last_receipt CHECK(command_id<>'SERIAL-P1' OR serial_id<>'SN-2' OR state<>'PICKED')");
        try {try(var session=sessions.openSession(false)) {assertThrows(RuntimeException.class,() -> new OutboundOrderService(session,clock).consumePick("ENT-1","WH-A",line,"SERIAL-RESULT","SERIAL-P1","APPLIED","SERIAL-POSTING",new BigDecimal("2")));session.rollback();}}
        finally {jdbc.execute("ALTER TABLE outbound_serial_pick DROP CHECK fail_serial_last_receipt");}
        assertEquals(0,jdbc.queryForObject("SELECT picked_posted_qty FROM outbound_line WHERE id=?",BigDecimal.class,line).signum());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE command_id='SERIAL-P1' AND state='PICKED'",Integer.class));
        try(var session=sessions.openSession(false)) {var service=new OutboundOrderService(session,clock);service.consumePick("ENT-1","WH-A",line,"SERIAL-RESULT","SERIAL-P1","APPLIED","SERIAL-POSTING",new BigDecimal("2"));service.consumePick("ENT-1","WH-A",line,"SERIAL-RESULT","SERIAL-P1","APPLIED","SERIAL-POSTING",new BigDecimal("2"));session.commit();}
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE command_id='SERIAL-P1' AND state='PICKED'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT picked_posted_qty FROM outbound_line WHERE id=?",BigDecimal.class,line).compareTo(new BigDecimal("2")));
    }
    private static com.lrj.wms.contract.messaging.SerialExecutionSelection selection(String... serials) {
        return new com.lrj.wms.contract.messaging.SerialExecutionSelection(1,java.util.Arrays.stream(serials).map(sn -> new com.lrj.wms.contract.messaging.SerialExecutionSelection.Identity(sn,1L)).toList());
    }

    @Test void partialSerialShipmentConsumesOnlyPostedOriginalIdentitiesAndReceiptIsAtomic() {
        Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);String order,line;
        try(var session=sessions.openSession(false)) {
            var service=new OutboundOrderService(session,clock);
            order=service.createFromAllocation("ENT-1","WH-A","ALLOC-IDENTITY-SHIP","ATT-IDENTITY-SHIP","OWNER",null,
                    List.of(Map.of("orderLineId","SHIP-LINE","skuId","SERIAL-SKU","qty",new BigDecimal("3"),"baseUnit","EA"))).get("id").toString();
            authorizeForTest(session,"ENT-1","WH-A",order,"ATT-IDENTITY-SHIP","AUTH-IDENTITY-SHIP");
            String task=service.planPickTask("ENT-1","WH-A",order,"SHIP-LINE","LOC","STAGE",new BigDecimal("3"),"IDENTITY-SHIP-TASK").get("taskId").toString();
            var picked=new OutboundPostingService(session,clock).pick("ENT-1","WH-A",task,"IDENTITY-SHIP-PICK","actor",new BigDecimal("3"),"IDENTITY-SHIP-PART","NO_LOT",selection("SHIP-SN1","SHIP-SN2","SHIP-SN3"));
            line=picked.get("lineId").toString();service.pack("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-PACK",new BigDecimal("3"));session.commit();
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("SERIAL_SHIP_CONFLICT",assertThrows(OutboundException.class,() -> new OutboundPostingService(session,clock).ship("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-EARLY","actor",BigDecimal.ONE,"EARLY","STAGE","NO_LOT",selection("SHIP-SN1"))).code());session.rollback();
        }
        try(var session=sessions.openSession(false)) {
            new OutboundOrderService(session,clock).consumePick("ENT-1","WH-A",line,"IDENTITY-PICK-EVENT","IDENTITY-SHIP-PICK","APPLIED","PICK-POSTING",new BigDecimal("3"));session.commit();
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("SERIAL_SHIP_CONFLICT",assertThrows(OutboundException.class,() -> new OutboundPostingService(session,clock).ship("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-NO-SN","actor",BigDecimal.ONE,"NO-SN","STAGE","NO_LOT")).code());session.rollback();
        }
        jdbc.execute("ALTER TABLE outbound_serial_pick ADD CONSTRAINT fail_ship_claim CHECK(serial_id<>'SHIP-SN2' OR shipment_command_id IS NULL)");
        try {try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,() -> new OutboundPostingService(session,clock).ship("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-SHIP","actor",new BigDecimal("2"),"IDENTITY-SHIP-PARTIAL","STAGE","NO_LOT",selection("SHIP-SN1","SHIP-SN2")));session.rollback();
        }} finally {jdbc.execute("ALTER TABLE outbound_serial_pick DROP CHECK fail_ship_claim");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='IDENTITY-SHIP'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE shipment_command_id='IDENTITY-SHIP'",Integer.class));
        try(var session=sessions.openSession(false)) {
            var posting=new OutboundPostingService(session,clock);
            posting.ship("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-SHIP","actor",new BigDecimal("2"),"IDENTITY-SHIP-PARTIAL","STAGE","NO_LOT",selection("SHIP-SN1","SHIP-SN2"));
            assertEquals("IDENTITY-SHIP",posting.ship("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-SHIP-REPLAY","actor",new BigDecimal("2"),"IDENTITY-SHIP-PARTIAL","STAGE","NO_LOT",selection("ship-sn2","ship-sn1")).get("commandId"));
            var available=session.getMapper(OutboundSerialMapper.class).shippable("ENT-1","WH-A",order,"SHIP-LINE","STAGE","NO_LOT",com.lrj.wms.runtime.web.CursorPage.parse(10,null,"test"));
            assertEquals(1,available.size());assertEquals("SHIP-SN3",available.getFirst().get("serialId"));session.commit();
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("SERIAL_SHIP_CONFLICT",assertThrows(OutboundException.class,() -> new OutboundPostingService(session,clock).ship("ENT-1","WH-A",order,"SHIP-LINE","IDENTITY-DOUBLE","actor",BigDecimal.ONE,"DOUBLE","STAGE","NO_LOT",selection("SHIP-SN1"))).code());session.rollback();
        }
        jdbc.execute("ALTER TABLE outbound_serial_pick ADD CONSTRAINT fail_ship_receipt CHECK(serial_id<>'SHIP-SN2' OR shipment_posted_at IS NULL)");
        try {try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,() -> new OutboundOrderService(session,clock).consumeShip("ENT-1","WH-A",line,"IDENTITY-SHIP-EVENT","IDENTITY-SHIP","APPLIED","SHIP-POSTING",new BigDecimal("2")));session.rollback();
        }} finally {jdbc.execute("ALTER TABLE outbound_serial_pick DROP CHECK fail_ship_receipt");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE shipment_command_id='IDENTITY-SHIP' AND shipment_posted_at IS NOT NULL",Integer.class));
        try(var session=sessions.openSession(false)) {
            var service=new OutboundOrderService(session,clock);
            assertEquals(true,service.consumeShip("ENT-1","WH-A",line,"IDENTITY-SHIP-EVENT","IDENTITY-SHIP","APPLIED","SHIP-POSTING",new BigDecimal("2")).get("consumed"));
            assertEquals(false,service.consumeShip("ENT-1","WH-A",line,"IDENTITY-SHIP-EVENT","IDENTITY-SHIP","APPLIED","SHIP-POSTING",new BigDecimal("2")).get("consumed"));session.commit();
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE shipment_command_id='IDENTITY-SHIP' AND shipment_posted_at IS NOT NULL",Integer.class));
        assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT shipped_physical_qty FROM outbound_line WHERE id=?",BigDecimal.class,line)));
        assertEquals(0,new BigDecimal("2").compareTo(jdbc.queryForObject("SELECT shipped_posted_qty FROM outbound_line WHERE id=?",BigDecimal.class,line)));
    }

    @Test
    void committedCancellationFencesLateAuthorizationAndPreservesUnknownPhysical() {
        Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
        String order;
        try(var session=sessions.openSession(false)) {
            var cancellation=cancellation("CANCEL-EARLY",clock);
            new CommittedCancellationService(session,clock).accept(cancellation);
            order=session.getMapper(OutboundOrderMapper.class).lockOrderByAttempt("ENT-1","WH-A","CANCEL-EARLY","CANCEL-EARLY").get("id").toString();
            String original=order;
            assertEquals("AUTH_CONFLICT",assertThrows(OutboundException.class,()->authorizeForTest(session,"ENT-1","WH-A",original,"CANCEL-EARLY","LATE-AUTH")).code());
            new CommittedCancellationService(session,clock).accept(cancellation);
            new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",order);
            session.commit();
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.compensationId'))='CANCEL-EARLY'",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT JSON_EXTRACT(payload_json,'$.outboundSchemaVersion') FROM source_command WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.compensationId'))='CANCEL-EARLY'",Integer.class));
        try(var session=sessions.openSession(false)) {
            new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",order);
            var row=session.getMapper(OutboundOrderMapper.class).cancellation("ENT-1","WH-A",order);
            assertEquals("PROCESSING",row.get("state"));assertEquals("CANCEL_POSTING_PENDING",row.get("error_code"));session.commit();
        }
        try(var session=sessions.openSession(false)) {
            var service=new OutboundOrderService(session,clock);
            var created=service.createFromAllocation("ENT-1","WH-A","CANCEL-UNKNOWN","CANCEL-UNKNOWN","OWNER-1",null,
                    List.of(Map.of("orderLineId","L-CANCEL","skuId","SKU-1","qty",new BigDecimal("5"),"baseUnit","EA"),
                            Map.of("orderLineId","L-SAFE","skuId","SKU-1","qty",new BigDecimal("2"),"baseUnit","EA")));
            String id=created.get("id").toString();authorizeForTest(session,"ENT-1","WH-A",id,"CANCEL-UNKNOWN","UNKNOWN-AUTH");
            String task=service.planPickTask("ENT-1","WH-A",id,"L-CANCEL","LOC-1","STG-1",new BigDecimal("5")).get("taskId").toString();
            session.getMapper(OutboundOrderMapper.class).claimTask("ENT-1","WH-A",task,"worker",1,"original-action","original-device",java.sql.Timestamp.from(NOW));
            new CommittedCancellationService(session,clock).accept(cancellation("CANCEL-UNKNOWN",clock));
            new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",id);
            assertEquals("PHYSICAL_OR_POSTING_UNKNOWN",session.getMapper(OutboundOrderMapper.class).cancellation("ENT-1","WH-A",id).get("error_code"));
            assertEquals("CANCELLATION_IN_PROGRESS",assertThrows(OutboundException.class,()->service.pickPartial("ENT-1","WH-A",task,"late-pick","actor",BigDecimal.ONE)).code());
            session.commit();
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.compensationId'))='CANCEL-UNKNOWN' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.reservationOrderLineId'))='L-SAFE'",Integer.class));
    }

    @Test
    void partialExecutionOnlyReleasesOriginalUnpickedRemainder() {
        Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);String id;
        try(var session=sessions.openSession(false)) {
            var service=new OutboundOrderService(session,clock);
            id=service.createFromAllocation("ENT-1","WH-A","CANCEL-PARTIAL","CANCEL-PARTIAL","OWNER-1",null,
                    List.of(Map.of("orderLineId","L-CANCEL","skuId","SKU-1","qty",new BigDecimal("5"),"baseUnit","EA"))).get("id").toString();
            authorizeForTest(session,"ENT-1","WH-A",id,"CANCEL-PARTIAL","PARTIAL-AUTH");
            String task=service.planPickTask("ENT-1","WH-A",id,"L-CANCEL","LOC-1","STG-1",new BigDecimal("5")).get("taskId").toString();
            var pick=new OutboundPostingService(session,clock).pick("ENT-1","WH-A",task,"PARTIAL-PICK","actor",new BigDecimal("3"),"PART","NO_LOT");
            service.consumePick("ENT-1","WH-A",pick.get("lineId").toString(),"PARTIAL-RECEIPT","PARTIAL-PICK","APPLIED","PARTIAL-POSTING",new BigDecimal("3"));
            new CommittedCancellationService(session,clock).accept(cancellation("CANCEL-PARTIAL",clock));
            new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",id);session.commit();
        }
        var command=jdbc.queryForMap("SELECT command_id,payload_json FROM source_command WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.compensationId'))='CANCEL-PARTIAL'");
        assertEquals(0,new BigDecimal(com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.readTree(command.get("payload_json").toString()).path("qty").asString()).compareTo(new BigDecimal("2")));
        try(var session=sessions.openSession(false)) {
            var line=session.getMapper(OutboundOrderMapper.class).lockLineByOrderLine("ENT-1","WH-A",id,"L-CANCEL");
            var service=new OutboundOrderService(session,clock);
            service.consumeCancel("ENT-1","WH-A",line.get("id").toString(),"CANCEL-PARTIAL-RESULT",command.get("command_id").toString(),"APPLIED","CANCEL-PARTIAL-POSTING",new BigDecimal("2"));
            new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",id);
            new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",id);
            assertEquals("PARTIALLY_COMPENSATED",session.getMapper(OutboundOrderMapper.class).cancellation("ENT-1","WH-A",id).get("state"));
            session.commit();
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM source_outbox WHERE command_id='CANCEL-PARTIAL' AND event_type='CommittedCancellationResultV1'",Integer.class));
    }

    @Test
    void concurrentRecoveryAndFailedFinalOutboxCannotDoubleCancel() throws Exception {
        Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);String id;
        try(var session=sessions.openSession(false)) {
            new CommittedCancellationService(session,clock).accept(cancellation("CANCEL-RACE",clock));
            id=session.getMapper(OutboundOrderMapper.class).lockOrderByAttempt("ENT-1","WH-A","CANCEL-RACE","CANCEL-RACE").get("id").toString();session.commit();
        }
        String command=com.lrj.wms.runtime.messaging.RuntimeMessage.hash(com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(
                List.of("cancel-compensation","ENT-1","WH-A","CANCEL-RACE","L-CANCEL","LOC-1","NO_LOT")));
        jdbc.execute("ALTER TABLE source_outbox ADD CONSTRAINT ck_cancel_last_outbox CHECK(command_id<>'"+command+"')");
        try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,()->new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",id));session.rollback();
            CommittedCancellationService.recoverOne(sessions,clock);
            assertEquals("COMPENSATION_STEP_FAILED",jdbc.queryForObject("SELECT error_code FROM outbound_cancellation WHERE order_id=?",String.class,id));
        } finally {jdbc.execute("ALTER TABLE source_outbox DROP CHECK ck_cancel_last_outbox");}
        assertEquals(0,jdbc.queryForObject("SELECT cancelled_qty FROM outbound_line WHERE order_id=?",BigDecimal.class,id).signum());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id=?",Integer.class,command));
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var futures=java.util.stream.IntStream.range(0,2).mapToObj(i->executor.submit(()->{
                start.await();try(var session=sessions.openSession(false)) {new CommittedCancellationService(session,clock).recover("ENT-1","WH-A",id);session.commit();}return true;
            })).toList();start.countDown();for(var result:futures) assertTrue(result.get(20,java.util.concurrent.TimeUnit.SECONDS));
        } finally {executor.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id=?",Integer.class,command));
        assertEquals(0,jdbc.queryForObject("SELECT cancelled_qty FROM outbound_line WHERE order_id=?",BigDecimal.class,id).compareTo(new BigDecimal("5")));
    }

    /** 明确的可信消息夹具，真实TC来源另由跨进程用例证明。 */
    private static com.lrj.wms.runtime.messaging.RuntimeMessage cancellation(String id,Clock clock) {
        var lines=new java.util.ArrayList<com.lrj.wms.contract.tcc.WarehouseTryRequest.Line>();
        lines.add(new com.lrj.wms.contract.tcc.WarehouseTryRequest.Line("L-CANCEL","SKU-1","LOC-1","NO_LOT",new BigDecimal("5"),"EA",0));
        if("CANCEL-UNKNOWN".equals(id)) lines.add(new com.lrj.wms.contract.tcc.WarehouseTryRequest.Line("L-SAFE","SKU-1","LOC-1","NO_LOT",new BigDecimal("2"),"EA",0));
        var request=new com.lrj.wms.contract.tcc.WarehouseTryRequest(1,"ENT-1","WH-A","OWNER-1",id,id,"CELL",1,lines);
        var value=new com.lrj.wms.contract.messaging.CommittedCancellation(1,id,"actor",request,
                new com.lrj.wms.contract.messaging.TcTerminalNotice(1,id,id,"xid-"+id,"cluster","wms-fulfillment","group",9));
        return new com.lrj.wms.runtime.messaging.RuntimeMessage(1,id,"wms-fulfillment","ENT-1","WH-A",
                com.lrj.wms.contract.messaging.CommittedCancellation.EVENT,id,1,clock.instant().toString(),null,
                com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.valueToTree(value));
    }

    private static void authorizeForTest(org.apache.ibatis.session.SqlSession session, String enterprise, String warehouse,
            String orderId, String attempt, String authorization) {
        if (!session.getConfiguration().hasMapper(com.lrj.wms.outbound.order.OutboundAuthorizationMapper.class)) {
            session.getConfiguration().addMapper(com.lrj.wms.outbound.order.OutboundAuthorizationMapper.class);
        }
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(session.getConnection(), true));
        String xid = "fixture-xid-" + attempt;
        String evidence = "fixture-committed/" + attempt;
        String hash = "e".repeat(64);
        jdbc.update("INSERT INTO outbound_tcc_evidence (id,enterprise_id,warehouse_id,attempt_id,xid,tc_observed_status,tc_terminal_evidence_ref,participant_set_hash,created_at,updated_at) VALUES (?,?,?,?,?,'Committed',?,?,NOW(6),NOW(6))",
                java.util.UUID.randomUUID().toString(), enterprise, warehouse, attempt, xid, evidence, hash);
        new com.lrj.wms.outbound.order.OutboundAuthorizationService(session, java.time.Clock.systemUTC()).authorize(
                enterprise, warehouse, orderId, "AUTH-KEY-" + attempt, "TEST-ACTOR", attempt, authorization, xid, evidence, hash);
    }
}
