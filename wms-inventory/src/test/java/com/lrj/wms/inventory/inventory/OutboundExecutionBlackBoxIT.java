package com.lrj.wms.inventory.inventory;

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
import com.lrj.wms.outbound.order.OutboundException;
import com.lrj.wms.outbound.order.OutboundOrderMapper;
import com.lrj.wms.outbound.order.OutboundOrderService;
import com.lrj.wms.outbound.protocol.SourceMapper;
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
 * S5-04：同 JVM 双库黑盒。短拣 3/5、包装发运、重复发运、取消回库、拣后效期拒绝发运 STARTED。
 * 不是 HTTP/履约/WCS/设备链路，不能当作 AC-13/14/15 生产通过。
 */
class OutboundExecutionBlackBoxIT {
    private static final Instant PICK_NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final Instant LOT_EXPIRES = Instant.parse("2026-09-12T12:00:00Z");
    private static final String DIGEST = "e".repeat(64);

    private static MySQLContainer inventoryMysql;
    private static MySQLContainer outboundMysql;
    private static SqlSessionFactory inventorySessions;
    private static SqlSessionFactory outboundSessions;
    private static JdbcTemplate inventoryJdbc;
    private static JdbcTemplate outboundJdbc;

    @BeforeAll
    static void prepare() {
        inventoryMysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        outboundMysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        inventoryMysql.start();
        outboundMysql.start();
        MysqlDataSource inventorySource = dataSource(inventoryMysql);
        MysqlDataSource outboundSource = dataSource(outboundMysql);
        Flyway.configure().dataSource(inventorySource)
                .locations("filesystem:" + migrationDir("wms-inventory")).load().migrate();
        Flyway.configure().dataSource(outboundSource)
                .locations("filesystem:" + migrationDir("wms-outbound")).load().migrate();
        inventoryJdbc = new JdbcTemplate(inventorySource);
        outboundJdbc = new JdbcTemplate(outboundSource);
        inventorySessions = sessions("inventory", inventorySource, MasterdataMapper.class, EffectMapper.class,
                InventoryMapper.class, OutboxMapper.class, CommandDedupMapper.class, StockCommandMapper.class);
        outboundSessions = sessions("outbound", outboundSource, SourceMapper.class, OutboundOrderMapper.class);
        Clock clock = Clock.fixed(PICK_NOW, ZoneOffset.UTC);
        SkuPolicy expirySku = SkuPolicy.create("SKU-EXP", "ENT-1", "SKU-EXP", "效期商品", "EA", 0, true, false, true, 1,
                MasterdataCodes.STATE_ACTIVE);
        try (SqlSession session = inventorySessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-2", "GATE-2", "ENT-1", "WH-A", "STG", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createSku(expirySku, "UNIT-EXP");
            masterdata.createLot(expirySku, "LOT-LIVE", "WH-A", "OWNER-1", "LIVE", "ENT-1/OWNER-1/SKU-EXP/LIVE",
                    Instant.parse("2026-08-01T00:00:00Z"), LOT_EXPIRES, "2026-09-12", 1);
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (inventoryMysql != null) {
            inventoryMysql.stop();
        }
        if (outboundMysql != null) {
            outboundMysql.stop();
        }
    }

    @Test
    void partialPickPackShipCancelAndDuplicateShip() {
        Clock clock = Clock.fixed(PICK_NOW, ZoneOffset.UTC);
        StockBucketKey storage = bucket("SKU-BB", MasterdataCodes.NO_LOT);
        StockBucketKey stage = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-2", "SKU-BB", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Quantity five = Quantity.parse("5", 0);
        Quantity three = Quantity.parse("3", 0);
        Quantity two = Quantity.parse("2", 0);
        String orderId;
        String lineId;
        String pickCommandId;
        String shipCommandId;
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            InventoryApplicationService stock = new InventoryApplicationService(inventory, clock);
            stock.receive("ENT-1", "WH-A", "OP-BB-RCV", "DOC-BB", "ACTOR", storage, five);
            stock.reserveTried("ENT-1", "WH-A", "OP-BB-TRY", "DOC-BB", "ACTOR", "ALLOC-BB", "ATT-BB", "xid-bb", 41L,
                    "ReservationTccAction", 1L, DIGEST,
                    List.of(new ReservationLineInput(storage, five, "L-BB")));
            stock.confirmTried("ENT-1", "WH-A", "OP-BB-CFM", "DOC-BB", "ACTOR", "ALLOC-BB", "ATT-BB", "xid-bb", 41L,
                    "ReservationTccAction");
            inventory.commit();
        }
        try (SqlSession outbound = outboundSessions.openSession(false)) {
            OutboundOrderService orders = new OutboundOrderService(outbound, clock);
            Map<String, Object> created = orders.createFromAllocation("ENT-1", "WH-A", "ALLOC-BB", "ATT-BB", "OWNER-1",
                    "AUTH-BB", List.of(Map.of("orderLineId", "L-BB", "skuId", "SKU-BB", "qty", new BigDecimal("5"),
                            "baseUnit", "EA")));
            authorizeForTest(outbound, "ENT-1", "WH-A", String.valueOf(created.get("id")), "ATT-BB", "AUTH-BB");
            orderId = String.valueOf(created.get("id"));
            Map<String, Object> planned = orders.planPickTask("ENT-1", "WH-A", orderId, "L-BB", "LOC-1", "LOC-2",
                    new BigDecimal("5"));
            Map<String, Object> pick = orders.pickPartial("ENT-1", "WH-A", String.valueOf(planned.get("taskId")),
                    "CMD-BB-PICK", "ACTOR", new BigDecimal("3"));
            pickCommandId = String.valueOf(pick.get("commandId"));
            lineId = String.valueOf(pick.get("lineId"));
            outbound.commit();
        }
        String pickPostingId;
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            Map<String, Object> applied = new StockCommandService(inventory, clock).applyPick("ENT-1", "WH-A",
                    StockCommandCodes.SOURCE_OUTBOUND, pickCommandId, orderId, "TASK-BB", lineId, "DOC-BB", "ACTOR",
                    "ALLOC-BB", "ATT-BB", storage, stage, three);
            assertEquals("APPLIED", applied.get("state"));
            pickPostingId = String.valueOf(applied.get("postingId"));
            inventory.commit();
        }
        try (SqlSession outbound = outboundSessions.openSession(false)) {
            OutboundOrderService orders = new OutboundOrderService(outbound, clock);
            Map<String, Object> consumed = orders.consumePick("ENT-1", "WH-A", lineId, "EVT-BB-PICK", pickCommandId,
                    "APPLIED", pickPostingId, new BigDecimal("3"));
            assertEquals(Boolean.TRUE, consumed.get("consumed"));
            orders.pack("ENT-1", "WH-A", orderId, "L-BB", "PKG-BB", new BigDecimal("3"));
            OutboundException over = assertThrows(OutboundException.class,
                    () -> orders.shipPartial("ENT-1", "WH-A", orderId, "L-BB", "CMD-BB-OVER", "ACTOR",
                            new BigDecimal("4")));
            assertEquals("OVER_SHIP", over.code());
            Map<String, Object> ship = orders.shipPartial("ENT-1", "WH-A", orderId, "L-BB", "CMD-BB-SHIP", "ACTOR",
                    new BigDecimal("3"));
            shipCommandId = String.valueOf(ship.get("commandId"));
            outbound.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            StockCommandService commands = new StockCommandService(inventory, clock);
            Map<String, Object> live = commands.startShipPermit("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-BB-SHIP-START", "TASK-BB-SHIP", 1L, orderId, "PART-SHIP", lineId, new BigDecimal("3"), stage);
            assertEquals("STARTED", live.get("permitState"));
            Map<String, Object> first = commands.applyShip("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    shipCommandId, orderId, "SHIP-POST", lineId, "DOC-BB", "ACTOR", "ALLOC-BB", "ATT-BB", stage, three);
            Map<String, Object> replay = commands.applyShip("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    shipCommandId, orderId, "SHIP-POST", lineId, "DOC-BB", "ACTOR", "ALLOC-BB", "ATT-BB", stage, three);
            assertEquals("APPLIED", first.get("state"));
            assertEquals(first.get("commandId"), replay.get("commandId"));
            inventory.commit();
        }
        String shipPostingId = inventoryJdbc.queryForObject(
                "SELECT id FROM stock_posting WHERE command_id=? AND posting_type='SHIPMENT'", String.class,
                shipCommandId);
        try (SqlSession outbound = outboundSessions.openSession(false)) {
            OutboundOrderService orders = new OutboundOrderService(outbound, clock);
            Map<String, Object> consumed = orders.consumeShip("ENT-1", "WH-A", lineId, "EVT-BB-SHIP", shipCommandId,
                    "APPLIED", shipPostingId, new BigDecimal("3"));
            assertEquals(Boolean.TRUE, consumed.get("consumed"));
            Map<String, Object> replayed = orders.consumeShip("ENT-1", "WH-A", lineId, "EVT-BB-SHIP", shipCommandId,
                    "APPLIED", shipPostingId, new BigDecimal("3"));
            assertEquals(Boolean.FALSE, replayed.get("consumed"));
            Map<String, Object> cancel = orders.cancelUnpicked("ENT-1", "WH-A", orderId, "L-BB", "CMD-BB-CXL", "ACTOR");
            assertEquals(0, new BigDecimal("2").compareTo((BigDecimal) cancel.get("cancelledQty")));
            outbound.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            new InventoryApplicationService(inventory, clock).releaseUnpicked("ENT-1", "WH-A", "OP-BB-REL", "DOC-BB",
                    "ACTOR", "ALLOC-BB", "ATT-BB", storage, two);
            inventory.commit();
        }
        assertEquals("SHIPPED", outboundJdbc.queryForObject("SELECT status FROM outbound_order WHERE id=?", String.class,
                orderId));
        assertEquals(0, outboundJdbc.queryForObject(
                "SELECT picked_physical_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, outboundJdbc.queryForObject(
                "SELECT shipped_physical_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, outboundJdbc.queryForObject(
                "SELECT shipped_posted_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("3.000000")));
        assertEquals(0, outboundJdbc.queryForObject(
                "SELECT cancelled_qty FROM outbound_line WHERE id=?", BigDecimal.class, lineId)
                .compareTo(new BigDecimal("2.000000")));
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-BB' AND location_id='LOC-1'",
                BigDecimal.class).compareTo(new BigDecimal("2.000000")));
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-BB' AND location_id='LOC-1'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-BB' AND location_id='LOC-2'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
        assertEquals(1, inventoryJdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE command_id=? AND posting_type='SHIPMENT'", Integer.class,
                shipCommandId));
        assertEquals(1, inventoryJdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_line WHERE order_line_id='L-BB' AND parent_line_id IS NOT NULL "
                        + "AND consumed_qty=3.000000",
                Integer.class));
        System.out.println("S5_BLACKBOX: same-JVM two-DB short-pick/pack/ship/cancel/replay; not HTTP/WCS/AC-13/14/15");
    }

    @Test
    void expiryAfterPickRejectsShipStartAndKeepsPick() {
        Clock pickClock = Clock.fixed(PICK_NOW, ZoneOffset.UTC);
        Clock shipClock = Clock.fixed(LOT_EXPIRES, ZoneOffset.UTC);
        StockBucketKey storage = bucket("SKU-EXP", "LOT-LIVE");
        StockBucketKey stage = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-2", "SKU-EXP", "LOT-LIVE",
                InventoryCodes.QUALITY_GOOD);
        Quantity five = Quantity.parse("5", 0);
        Quantity three = Quantity.parse("3", 0);
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            InventoryApplicationService stock = new InventoryApplicationService(inventory, pickClock);
            stock.receive("ENT-1", "WH-A", "OP-EXP-RCV", "DOC-EXP", "ACTOR", storage, five);
            stock.reserveTried("ENT-1", "WH-A", "OP-EXP-TRY", "DOC-EXP", "ACTOR", "ALLOC-EXP", "ATT-EXP", "xid-exp", 42L,
                    "ReservationTccAction", 1L, DIGEST,
                    List.of(new ReservationLineInput(storage, five, "L-EXP")));
            stock.confirmTried("ENT-1", "WH-A", "OP-EXP-CFM", "DOC-EXP", "ACTOR", "ALLOC-EXP", "ATT-EXP", "xid-exp", 42L,
                    "ReservationTccAction");
            inventory.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            new StockCommandService(inventory, pickClock).applyPick("ENT-1", "WH-A", StockCommandCodes.SOURCE_OUTBOUND,
                    "CMD-EXP-PICK", "ORD-EXP", "TASK-EXP", "LINE-EXP", "DOC-EXP", "ACTOR", "ALLOC-EXP", "ATT-EXP",
                    storage, stage, three);
            inventory.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            InventoryException expired = assertThrows(InventoryException.class,
                    () -> new StockCommandService(inventory, shipClock).startShipPermit("ENT-1", "WH-A",
                            StockCommandCodes.SOURCE_OUTBOUND, "CMD-EXP-SHIP-START", "TASK-EXP-SHIP", 1L, "ORD-EXP",
                            "PART-EXP", "LINE-EXP", new BigDecimal("3"), stage));
            assertEquals("LOT_EXPIRED", expired.code());
            inventory.rollback();
        }
        assertEquals(1, inventoryJdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_posting WHERE command_id='CMD-EXP-PICK' AND posting_type='PICK'",
                Integer.class));
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-EXP' AND location_id='LOC-2'",
                BigDecimal.class).compareTo(new BigDecimal("3.000000")));
        assertEquals(0, inventoryJdbc.queryForObject(
                "SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-EXP' AND location_id='LOC-2'",
                BigDecimal.class).compareTo(new BigDecimal("3.000000")));
        System.out.println("S5_BLACKBOX: explicit lot expires_at; pick kept after LOT_EXPIRED ship STARTED");
    }

    private static StockBucketKey bucket(String skuId, String lotId) {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", skuId, lotId, InventoryCodes.QUALITY_GOOD);
    }

    private static MysqlDataSource dataSource(MySQLContainer mysql) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }

    @SafeVarargs
    private static SqlSessionFactory sessions(String id, MysqlDataSource source, Class<?>... mappers) {
        Configuration config = new Configuration(new Environment(id, new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
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
