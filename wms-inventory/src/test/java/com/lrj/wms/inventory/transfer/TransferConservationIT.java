package com.lrj.wms.inventory.transfer;

import com.lrj.wms.fulfillment.TransferMapper;
import com.lrj.wms.fulfillment.TransferService;
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
 * S6-04：履约库与库存库分事务，两仓库存 + 在途 + 损耗守恒。
 * 不是 AC-16 生产 HTTP。未发明 OQ-03。
 */
class TransferConservationIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:20:00Z");
    private static MySQLContainer inventoryMysql;
    private static MySQLContainer fulfillmentMysql;
    private static SqlSessionFactory inventorySessions;
    private static SqlSessionFactory fulfillmentSessions;
    private static JdbcTemplate inventoryJdbc;
    private static JdbcTemplate fulfillmentJdbc;

    @BeforeAll
    static void prepare() {
        inventoryMysql = mysql("wms_inventory");
        fulfillmentMysql = mysql("wms_fulfillment");
        inventoryMysql.start();
        fulfillmentMysql.start();
        MysqlDataSource inventorySource = dataSource(inventoryMysql);
        MysqlDataSource fulfillmentSource = dataSource(fulfillmentMysql);
        Flyway.configure().dataSource(inventorySource)
                .locations("filesystem:" + migrationDir("wms-inventory")).load().migrate();
        Flyway.configure().dataSource(fulfillmentSource)
                .locations("filesystem:" + migrationDir("wms-fulfillment").resolve("fulfillment")).load().migrate();
        inventoryJdbc = new JdbcTemplate(inventorySource);
        fulfillmentJdbc = new JdbcTemplate(fulfillmentSource);
        inventorySessions = sessions("inventory", inventorySource, MasterdataMapper.class, InventoryMapper.class,
                OutboxMapper.class, CommandDedupMapper.class);
        fulfillmentSessions = sessions("fulfillment", fulfillmentSource, TransferMapper.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = inventorySessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createWarehouse("WH-B", "ENT-1", "NGB", "宁波仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-A", "GATE-A", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-B", "GATE-B", "ENT-1", "WH-B", "B-01", "B", "STORAGE", new BigDecimal("100"),
                    "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (inventoryMysql != null) {
            inventoryMysql.stop();
        }
        if (fulfillmentMysql != null) {
            fulfillmentMysql.stop();
        }
    }

    @Test
    void twoWarehousesPlusTransitAndLossConserve() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey source = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-A", "SKU-CV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        StockBucketKey dest = StockBucketKey.of("ENT-1", "WH-B", "OWNER-1", "LOC-B", "SKU-CV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            TransferStockService stock = new TransferStockService(inventory, clock);
            stock.receive("ENT-1", "WH-A", "OP-CV-RCV", "DOC-CV", "ACTOR", source, Quantity.parse("5", 0));
            inventory.commit();
        }
        try (SqlSession fulfillment = fulfillmentSessions.openSession(false)) {
            TransferService transfers = new TransferService(fulfillment, clock);
            transfers.create("ENT-1", "TR-CV", "WH-A", "WH-B",
                    List.of(Map.of("lineId", "TL-CV", "skuId", "SKU-CV", "plannedQty", new BigDecimal("5"))));
            fulfillment.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            TransferStockService stock = new TransferStockService(inventory, clock);
            stock.issue("ENT-1", "WH-A", "OP-CV-ISSUE", "TR-CV", "ACTOR", source, Quantity.parse("4", 0));
            assertEquals(Boolean.TRUE, stock.issue("ENT-1", "WH-A", "OP-CV-ISSUE", "TR-CV", "ACTOR", source,
                    Quantity.parse("4", 0)).get("replayed"));
            inventory.commit();
        }
        try (SqlSession fulfillment = fulfillmentSessions.openSession(false)) {
            TransferService transfers = new TransferService(fulfillment, clock);
            transfers.issue("ENT-1", "TR-CV", "TL-CV", "OP-CV-ISSUE", new BigDecimal("4"));
            assertEquals(Boolean.TRUE, transfers.issue("ENT-1", "TR-CV", "TL-CV", "OP-CV-ISSUE", new BigDecimal("4"))
                    .get("replayed"));
            fulfillment.commit();
        }
        Map<String, Object> auth;
        try (SqlSession fulfillment = fulfillmentSessions.openSession(false)) {
            TransferService transfers = new TransferService(fulfillment, clock);
            auth = transfers.authorizeReceipt("ENT-1", "TR-CV", "TL-CV", "CLI-CV-1", new BigDecimal("3"));
            fulfillment.commit();
        }
        try (SqlSession inventory = inventorySessions.openSession(false)) {
            new TransferStockService(inventory, clock).receive("ENT-1", "WH-B", "OP-CV-RCV-B", "TR-CV", "ACTOR", dest,
                    Quantity.parse("3", 0));
            inventory.commit();
        }
        try (SqlSession fulfillment = fulfillmentSessions.openSession(false)) {
            TransferService transfers = new TransferService(fulfillment, clock);
            transfers.receive("ENT-1", "TR-CV", "TL-CV", "OP-CV-RCV-B", String.valueOf(auth.get("authorizationId")),
                    ((Number) auth.get("tokenVersion")).longValue(), new BigDecimal("3"), "LOT-B");
            transfers.confirmLoss("ENT-1", "TR-CV", "TL-CV", "OP-CV-LOSS", new BigDecimal("1"));
            fulfillment.commit();
        }
        BigDecimal sourceOnHand = inventoryJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-CV'", BigDecimal.class);
        BigDecimal destOnHand = inventoryJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-B' AND sku_id='SKU-CV'", BigDecimal.class);
        BigDecimal issued = fulfillmentJdbc.queryForObject(
                "SELECT issued_qty FROM transfer_line WHERE id='TL-CV'", BigDecimal.class);
        BigDecimal received = fulfillmentJdbc.queryForObject(
                "SELECT received_qty FROM transfer_line WHERE id='TL-CV'", BigDecimal.class);
        BigDecimal loss = fulfillmentJdbc.queryForObject(
                "SELECT loss_confirmed_qty FROM transfer_line WHERE id='TL-CV'", BigDecimal.class);
        BigDecimal quota = fulfillmentJdbc.queryForObject(
                "SELECT active_receipt_quota FROM transfer_line WHERE id='TL-CV'", BigDecimal.class);
        BigDecimal transit = issued.subtract(received).subtract(loss);
        assertEquals(0, sourceOnHand.compareTo(new BigDecimal("1.000000")));
        assertEquals(0, destOnHand.compareTo(new BigDecimal("3.000000")));
        assertEquals(0, issued.compareTo(new BigDecimal("4.000000")));
        assertEquals(0, received.compareTo(new BigDecimal("3.000000")));
        assertEquals(0, loss.compareTo(new BigDecimal("1.000000")));
        assertEquals(0, quota.compareTo(BigDecimal.ZERO));
        assertEquals(0, transit.compareTo(BigDecimal.ZERO));
        assertEquals(0, sourceOnHand.add(destOnHand).add(transit).add(loss).compareTo(new BigDecimal("5.000000")));
        assertEquals(1, inventoryJdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-CV-ISSUE'",
                Integer.class));
        assertEquals(1, fulfillmentJdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_fact WHERE operation_id='OP-CV-ISSUE' AND action='ISSUE'", Integer.class));
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
}
