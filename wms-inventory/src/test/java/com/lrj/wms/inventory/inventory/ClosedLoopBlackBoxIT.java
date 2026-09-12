package com.lrj.wms.inventory.inventory;

import com.lrj.wms.fulfillment.FulfillmentMapper;
import com.lrj.wms.fulfillment.FulfillmentService;
import com.lrj.wms.inbound.protocol.SourceMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptService;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.domain.StockCommandCodes;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.inventory.infrastructure.StockCommandMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.tcc.ReservationTccAction;
import com.lrj.wms.outbound.order.OutboundOrderMapper;
import com.lrj.wms.outbound.order.OutboundOrderService;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * S5-06 / EG-04：同 JVM 四库闭环。收货入账→两仓预占→授权→拣货发运→来源回执，含一次丢失响应重放。
 * 不是 HTTP/真实 TC/WCS/设备，不能当作 AC-10/12/13/14/15/25 生产通过。未发明 OQ-03。
 */
class ClosedLoopBlackBoxIT {
    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");
    private static final String ENT = "ENT-CL";
    private static final String SKU = "SKU-LOOP";
    private static final String OWNER = "OWNER-1";
    private static final String ALLOC = "ALLOC-CL";
    private static final String DIGEST = "c".repeat(64);
    private static final String XID = "xid-cl-01";

    private static MySQLContainer inboundMysql;
    private static MySQLContainer inventoryMysql;
    private static MySQLContainer outboundMysql;
    private static MySQLContainer fulfillmentMysql;
    private static SqlSessionFactory inboundSessions;
    private static SqlSessionFactory inventorySessions;
    private static SqlSessionFactory outboundSessions;
    private static SqlSessionFactory fulfillmentSessions;
    private static JdbcTemplate inboundJdbc;
    private static JdbcTemplate inventoryJdbc;
    private static JdbcTemplate outboundJdbc;
    private static JdbcTemplate fulfillmentJdbc;

    @BeforeAll
    static void prepare() {
        inboundMysql = mysql("wms_inbound");
        inventoryMysql = mysql("wms_inventory");
        outboundMysql = mysql("wms_outbound");
        fulfillmentMysql = mysql("wms_fulfillment");
        inboundMysql.start();
        inventoryMysql.start();
        outboundMysql.start();
        fulfillmentMysql.start();
        MysqlDataSource inboundSource = dataSource(inboundMysql);
        MysqlDataSource inventorySource = dataSource(inventoryMysql);
        MysqlDataSource outboundSource = dataSource(outboundMysql);
        MysqlDataSource fulfillmentSource = dataSource(fulfillmentMysql);
        Flyway.configure().dataSource(inboundSource)
                .locations("filesystem:" + migrationDir("wms-inbound")).load().migrate();
        Flyway.configure().dataSource(inventorySource)
                .locations("filesystem:" + migrationDir("wms-inventory")).load().migrate();
        Flyway.configure().dataSource(outboundSource)
                .locations("filesystem:" + migrationDir("wms-outbound")).load().migrate();
        Flyway.configure().dataSource(fulfillmentSource)
                .locations("filesystem:" + migrationDir("wms-fulfillment").resolve("fulfillment")).load().migrate();
        inboundJdbc = new JdbcTemplate(inboundSource);
        inventoryJdbc = new JdbcTemplate(inventorySource);
        outboundJdbc = new JdbcTemplate(outboundSource);
        fulfillmentJdbc = new JdbcTemplate(fulfillmentSource);
        inboundSessions = sessions("inbound", inboundSource, SourceMapper.class, InboundReceiptMapper.class);
        inventorySessions = sessions("inventory", inventorySource, MasterdataMapper.class, EffectMapper.class,
                InventoryMapper.class, OutboxMapper.class, CommandDedupMapper.class, StockCommandMapper.class);
        outboundSessions = sessions("outbound", outboundSource, com.lrj.wms.outbound.protocol.SourceMapper.class,
                OutboundOrderMapper.class);
        fulfillmentSessions = sessions("fulfillment", fulfillmentSource, FulfillmentMapper.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SkuPolicy sku = SkuPolicy.create(SKU, ENT, SKU, "闭环商品", "EA", 0, false, false, false, 1,
                MasterdataCodes.STATE_ACTIVE);
        try (SqlSession session = inventorySessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", ENT, "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createWarehouse("WH-B", ENT, "SZX", "深圳仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-A1", "GATE-A1", ENT, "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-A2", "GATE-A2", ENT, "WH-A", "A-STG", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-B1", "GATE-B1", ENT, "WH-B", "B-01", "B", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-B2", "GATE-B2", ENT, "WH-B", "B-STG", "B", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createSku(sku, "UNIT-LOOP");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (inboundMysql != null) {
            inboundMysql.stop();
        }
        if (inventoryMysql != null) {
            inventoryMysql.stop();
        }
        if (outboundMysql != null) {
            outboundMysql.stop();
        }
        if (fulfillmentMysql != null) {
            fulfillmentMysql.stop();
        }
    }

    @Test
    void receiveReserveAuthorizePickShipAndReplayLostResponse() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        receiveAndPost("WH-A", "ASN-A", "LINE-A", "CMD-R-A", "PART-A", "LOC-A1");
        receiveAndPost("WH-B", "ASN-B", "LINE-B", "CMD-R-B", "PART-B", "LOC-B1");
        assertBalance("LOC-A1", "5.000000", "0.000000");
        assertBalance("LOC-B1", "5.000000", "0.000000");

        String attemptId;
        try (SqlSession fulfillment = fulfillmentSessions.openSession(false)) {
            FulfillmentService ff = new FulfillmentService(fulfillment, clock);
            String orderId = String.valueOf(ff.createOrder(ENT, "OMS", "SO-CL-01", DIGEST,
                    List.of(Map.of("sourceLineId", "L1", "skuId", SKU, "requestedQty", new BigDecimal("6"),
                            "baseUnit", "EA")), 1).get("id"));
            attemptId = String.valueOf(ff.createAttempt(ENT, orderId, NOW.plusSeconds(3600), List.of("WH-A", "WH-B"),
                    List.of(
                            Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", SKU, "qty",
                                    new BigDecimal("3"), "baseUnit", "EA"),
                            Map.of("warehouseId", "WH-B", "orderLineId", "L1", "skuId", SKU, "qty",
                                    new BigDecimal("3"), "baseUnit", "EA"))).get("id"));
            ff.claimLaunch(ENT, attemptId, "exec-1");
            ff.bindXid(ENT, attemptId, "exec-1", XID);
            fulfillment.commit();
        }

        reserveConfirm("WH-A", attemptId, 11L, bucket("WH-A", "LOC-A1"), "OP-TRY-A", "OP-CFM-A");
        reserveConfirm("WH-B", attemptId, 12L, bucket("WH-B", "LOC-B1"), "OP-TRY-B", "OP-CFM-B");
        try (SqlSession fulfillment = fulfillmentSessions.openSession(false)) {
            FulfillmentService ff = new FulfillmentService(fulfillment, clock);
            String resA = inventoryJdbc.queryForObject(
                    "SELECT id FROM reservation WHERE warehouse_id='WH-A' AND allocation_id=? AND attempt_id=?",
                    String.class, ALLOC, attemptId);
            String resB = inventoryJdbc.queryForObject(
                    "SELECT id FROM reservation WHERE warehouse_id='WH-B' AND allocation_id=? AND attempt_id=?",
                    String.class, ALLOC, attemptId);
            ff.bindParticipant(ENT, attemptId, "WH-A", XID, 11L, ReservationTccAction.ACTION_NAME, resA, 1L, "TRIED");
            ff.bindParticipant(ENT, attemptId, "WH-B", XID, 12L, ReservationTccAction.ACTION_NAME, resB, 1L, "TRIED");
            ff.observeTc(ENT, attemptId, FulfillmentService.TC_COMMITTED, "{\"xid\":\"" + XID + "\",\"status\":9}");
            ff.observeParticipant(ENT, attemptId, "WH-A", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
            ff.observeParticipant(ENT, attemptId, "WH-B", FulfillmentService.PARTICIPANT_CONFIRMED, 1L);
            ff.markAllocated(ENT, attemptId);
            fulfillment.commit();
        }
        assertEquals("ALLOCATED", fulfillmentJdbc.queryForObject(
                "SELECT state FROM allocation_attempt WHERE id=?", String.class, attemptId));
        assertEquals("CONFIRMED", inventoryJdbc.queryForObject(
                "SELECT state FROM reservation WHERE warehouse_id='WH-A' AND allocation_id=? AND attempt_id=?",
                String.class, ALLOC, attemptId));
        assertBalance("LOC-A1", "5.000000", "3.000000");
        assertBalance("LOC-B1", "5.000000", "3.000000");

        String authA = fulfillmentJdbc.queryForObject(
                "SELECT operation_id FROM fulfillment_outbox WHERE attempt_id=? AND warehouse_id='WH-A' "
                        + "AND event_type='ExecutionAuthorizationRequested'",
                String.class, attemptId);
        String authB = fulfillmentJdbc.queryForObject(
                "SELECT operation_id FROM fulfillment_outbox WHERE attempt_id=? AND warehouse_id='WH-B' "
                        + "AND event_type='ExecutionAuthorizationRequested'",
                String.class, attemptId);
        pickShip("WH-A", attemptId, authA, "LOC-A1", "LOC-A2", true);
        pickShip("WH-B", attemptId, authB, "LOC-B1", "LOC-B2", false);

        assertBalance("LOC-A1", "2.000000", "0.000000");
        assertBalance("LOC-B1", "2.000000", "0.000000");
        assertBalance("LOC-A2", "0.000000", "0.000000");
        assertBalance("LOC-B2", "0.000000", "0.000000");
        assertEquals(2, outboundJdbc.queryForObject("SELECT COUNT(*) FROM outbound_order WHERE status='SHIPPED'",
                Integer.class));
        assertEquals(2, inventoryJdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE posting_type='SHIPMENT'", Integer.class));
        assertEquals(2, inboundJdbc.queryForObject(
                "SELECT COUNT(*) FROM source_command WHERE action='RECEIVE' AND state='APPLIED'", Integer.class));
        System.out.println("S5_CLOSED_LOOP: same-JVM four-DB receive/reserve/pick/ship + one lost-response replay; "
                + "not HTTP/TC/WCS/AC-10/12/13/14/15/25");
    }

    private static void receiveAndPost(String warehouseId, String asnId, String lineId, String commandId, String partId,
            String locationId) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession inbound = inboundSessions.openSession(false)) {
            InboundReceiptService receipts = new InboundReceiptService(inbound, clock);
            receipts.createOrder(ENT, warehouseId, asnId, "OMS", "EXT-" + warehouseId, OWNER,
                    List.of(Map.of("lineId", lineId, "externalLineId", "L1", "skuId", SKU,
                            "expectedQty", new BigDecimal("5"), "unit", "EA")));
            Map<String, Object> received = receipts.receive(ENT, warehouseId, asnId, lineId, commandId, partId, "ACTOR",
                    new BigDecimal("5"));
            assertEquals(commandId, received.get("commandId"));
            inbound.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            Map<String, Object> first = new StockCommandService(inventory, clock).applyReceive(ENT, warehouseId,
                    StockCommandCodes.SOURCE_INBOUND, commandId, asnId, partId, lineId, "DOC-" + commandId, "ACTOR",
                    "EXEC-" + commandId, bucket(warehouseId, locationId), Quantity.parse("5", 0));
            Map<String, Object> replay = new StockCommandService(inventory, clock).applyReceive(ENT, warehouseId,
                    StockCommandCodes.SOURCE_INBOUND, commandId, asnId, partId, lineId, "DOC-" + commandId, "ACTOR",
                    "EXEC-" + commandId, bucket(warehouseId, locationId), Quantity.parse("5", 0));
            assertEquals("APPLIED", first.get("state"));
            assertEquals(first.get("commandId"), replay.get("commandId"));
            inventory.commit();
        }
        String postingId = inventoryJdbc.queryForObject("SELECT id FROM stock_posting WHERE command_id=?", String.class,
                commandId);
        try (SqlSession inbound = inboundSessions.openSession(false)) {
            InboundReceiptService receipts = new InboundReceiptService(inbound, clock);
            Map<String, Object> consumed = receipts.consumeReceive(ENT, warehouseId, lineId, "EVT-" + commandId,
                    commandId, "APPLIED", postingId, new BigDecimal("5"));
            assertEquals(Boolean.TRUE, consumed.get("consumed"));
            Map<String, Object> replayed = receipts.consumeReceive(ENT, warehouseId, lineId, "EVT-" + commandId,
                    commandId, "APPLIED", postingId, new BigDecimal("5"));
            assertEquals(Boolean.FALSE, replayed.get("consumed"));
            inbound.commit();
        }
        assertEquals(1, inventoryJdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE command_id=? AND posting_type='RECEIPT'", Integer.class,
                commandId));
        assertEquals(0, inboundJdbc.queryForObject(
                "SELECT received_posted_qty FROM inbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("5.000000")));
    }

    private static void reserveConfirm(String warehouseId, String attemptId, long branchId, StockBucketKey storage,
            String tryOp, String confirmOp) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            InventoryApplicationService stock = new InventoryApplicationService(inventory, clock);
            stock.reserveTried(ENT, warehouseId, tryOp, "DOC-CL", "ACTOR", ALLOC, attemptId, XID, branchId,
                    ReservationTccAction.ACTION_NAME, 1L, DIGEST,
                    List.of(new ReservationLineInput(storage, Quantity.parse("3", 0), "L1")));
            stock.confirmTried(ENT, warehouseId, confirmOp, "DOC-CL", "ACTOR", ALLOC, attemptId, XID, branchId,
                    ReservationTccAction.ACTION_NAME);
            inventory.commit();
        }
    }

    private static void pickShip(String warehouseId, String attemptId, String authId, String storageLoc,
            String stageLoc, boolean replayLostPick) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey storage = bucket(warehouseId, storageLoc);
        StockBucketKey stage = bucket(warehouseId, stageLoc);
        String orderId;
        String lineId;
        String taskId;
        String pickCommandId;
        String shipCommandId;
        try (SqlSession outbound = outboundSessions.openSession(false)) {
            OutboundOrderService orders = new OutboundOrderService(outbound, clock);
            Map<String, Object> created = orders.createFromAllocation(ENT, warehouseId, ALLOC, attemptId, OWNER, authId,
                    List.of(Map.of("orderLineId", "L1", "skuId", SKU, "qty", new BigDecimal("3"), "baseUnit", "EA")));
            authorizeForTest(outbound, ENT, warehouseId, String.valueOf(created.get("id")), attemptId, authId);
            orderId = String.valueOf(created.get("id"));
            Map<String, Object> planned = orders.planPickTask(ENT, warehouseId, orderId, "L1", storageLoc, stageLoc,
                    new BigDecimal("3"));
            taskId = String.valueOf(planned.get("taskId"));
            Map<String, Object> pick = orders.pickPartial(ENT, warehouseId, taskId, "CMD-P-" + warehouseId, "ACTOR",
                    new BigDecimal("3"));
            pickCommandId = String.valueOf(pick.get("commandId"));
            lineId = String.valueOf(pick.get("lineId"));
            outbound.commit();
        }
        String pickPostingId;
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            Map<String, Object> first = new StockCommandService(inventory, clock).applyPick(ENT, warehouseId,
                    StockCommandCodes.SOURCE_OUTBOUND, pickCommandId, orderId, taskId, lineId,
                    "DOC-P-" + warehouseId, "ACTOR", ALLOC, attemptId, storage, stage, Quantity.parse("3", 0));
            assertEquals("APPLIED", first.get("state"));
            pickPostingId = String.valueOf(first.get("postingId"));
            inventory.commit();
        }
        if (replayLostPick) {
            try (SqlSession inventory = inventorySessions.openSession(false)) {
                Map<String, Object> replay = new StockCommandService(inventory, clock).applyPick(ENT, warehouseId,
                        StockCommandCodes.SOURCE_OUTBOUND, pickCommandId, orderId, taskId, lineId,
                        "DOC-P-" + warehouseId, "ACTOR", ALLOC, attemptId, storage, stage, Quantity.parse("3", 0));
                assertEquals("APPLIED", replay.get("state"));
                assertEquals(pickCommandId, replay.get("commandId"));
                inventory.commit();
            }
            assertEquals(1, inventoryJdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_posting WHERE command_id=? AND posting_type='PICK'", Integer.class,
                    pickCommandId));
        }
        try (SqlSession outbound = outboundSessions.openSession(false)) {
            OutboundOrderService orders = new OutboundOrderService(outbound, clock);
            Map<String, Object> consumed = orders.consumePick(ENT, warehouseId, lineId, "EVT-P-" + warehouseId,
                    pickCommandId, "APPLIED", pickPostingId, new BigDecimal("3"));
            assertEquals(Boolean.TRUE, consumed.get("consumed"));
            if (replayLostPick) {
                Map<String, Object> replayed = orders.consumePick(ENT, warehouseId, lineId, "EVT-P-" + warehouseId,
                        pickCommandId, "APPLIED", pickPostingId, new BigDecimal("3"));
                assertEquals(Boolean.FALSE, replayed.get("consumed"));
            }
            orders.pack(ENT, warehouseId, orderId, "L1", "PKG-" + warehouseId, new BigDecimal("3"));
            Map<String, Object> ship = orders.shipPartial(ENT, warehouseId, orderId, "L1", "CMD-S-" + warehouseId,
                    "ACTOR", new BigDecimal("3"));
            shipCommandId = String.valueOf(ship.get("commandId"));
            outbound.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            StockCommandService commands = new StockCommandService(inventory, clock);
            commands.startShipPermit(ENT, warehouseId, StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-S-START-" + warehouseId, "TASK-S-" + warehouseId, 1L, orderId, "PART-SHIP-" + warehouseId,
                    lineId, new BigDecimal("3"), stage);
            commands.applyShip(ENT, warehouseId, StockCommandCodes.SOURCE_OUTBOUND, shipCommandId, orderId,
                    "SHIP-POST-" + warehouseId, lineId, "DOC-S-" + warehouseId, "ACTOR", ALLOC, attemptId, stage,
                    Quantity.parse("3", 0));
            inventory.commit();
        }
        String shipPostingId = inventoryJdbc.queryForObject(
                "SELECT id FROM stock_posting WHERE command_id=? AND posting_type='SHIPMENT'", String.class,
                shipCommandId);
        try (SqlSession outbound = outboundSessions.openSession(false)) {
            Map<String, Object> consumed = new OutboundOrderService(outbound, clock).consumeShip(ENT, warehouseId,
                    lineId, "EVT-S-" + warehouseId, shipCommandId, "APPLIED", shipPostingId, new BigDecimal("3"));
            assertEquals(Boolean.TRUE, consumed.get("consumed"));
            outbound.commit();
        }
        assertEquals("SHIPPED", outboundJdbc.queryForObject("SELECT status FROM outbound_order WHERE id=?",
                String.class, orderId));
        assertEquals(1, inventoryJdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE command_id=? AND posting_type='PICK'", Integer.class,
                pickCommandId));
    }

    private static void assertBalance(String locationId, String onHand, String reserved) {
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id=? AND location_id=?", BigDecimal.class, SKU,
                locationId).compareTo(new BigDecimal(onHand)));
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT reserved_qty FROM stock_balance WHERE sku_id=? AND location_id=?", BigDecimal.class, SKU,
                locationId).compareTo(new BigDecimal(reserved)));
    }

    private static StockBucketKey bucket(String warehouseId, String locationId) {
        return StockBucketKey.of(ENT, warehouseId, OWNER, locationId, SKU, MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static MySQLContainer mysql(String database) {
        return new MySQLContainer("mysql:8.4.11").withDatabaseName(database).withUsername("wms")
                .withPassword(UUID.randomUUID().toString());
    }

    private static MysqlDataSource dataSource(MySQLContainer mysql) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }

    @SafeVarargs
    private static SqlSessionFactory sessions(String id, MysqlDataSource source, Class<?>... mappers) {
        Configuration config = new Configuration(new Environment(id, new JdbcTransactionFactory(), source));
        for (Class<?> mapper : mappers) {
            config.addMapper(mapper);
        }
        return new SqlSessionFactoryBuilder().build(config);
    }

    private static Path migrationDir(String module) {
        Path cwd = Path.of("").toAbsolutePath();
        Path nested = cwd.resolve(module).resolve("src/main/resources/db/migration");
        if (Files.isDirectory(nested)) {
            return nested;
        }
        Path sibling = cwd.getParent().resolve(module).resolve("src/main/resources/db/migration");
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        throw new IllegalStateException("找不到 " + module + " 迁移目录，cwd=" + cwd);
    }

    /** 仅本地测试的终态证据夹具；仍调用实际授权服务，不证明真实 TC 集成。 */
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
