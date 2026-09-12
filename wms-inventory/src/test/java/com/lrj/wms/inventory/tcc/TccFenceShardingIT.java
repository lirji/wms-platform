package com.lrj.wms.inventory.tcc;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.ReservationLineInput;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S4-04：二阶段按持久化仓上下文选择物理库，Fence 与预占同片事务。不是真实 TC，也不是 fulfillment 屏障。
 */
class TccFenceShardingIT {
    private static final Instant NOW = Instant.parse("2026-09-11T17:00:00Z");
    private static final String DIGEST = "e".repeat(64);

    private static MySQLContainer mysql;
    private static DataSource routed;
    private static DataSource cellA;
    private static DataSource cellB;
    private static JdbcTemplate jdbcA;
    private static JdbcTemplate jdbcB;
    private static ReservationTccAction action;
    private static org.apache.seata.rm.fence.SpringFenceHandler fence;
    private static InventoryApplicationService inventory;
    private static org.springframework.transaction.support.TransactionTemplate template;

    @BeforeAll
    static void prepare() throws Exception {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("bootstrap")
                .withUsername("probe").withPassword(UUID.randomUUID().toString());
        mysql.start();
        JdbcTemplate admin = new JdbcTemplate(source("bootstrap", "root"));
        Map<Object, Object> cells = new HashMap<>();
        for (String warehouse : List.of("WH-A", "WH-B")) {
            String database = warehouse.equals("WH-A") ? "inv_a" : "inv_b";
            admin.execute("CREATE DATABASE " + database);
            admin.execute("CREATE USER '" + database + "'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
            admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON " + database + ".* TO '" + database + "'@'%'");
            DataSource physical = source(database, database);
            Flyway.configure().dataSource(source(database, "root")).locations("classpath:db/migration").load().migrate();
            DataSource sharded = YamlShardingSphereDataSourceFactory.createDataSource(
                    Map.of("cell", physical), yaml(warehouse).getBytes(StandardCharsets.UTF_8));
            cells.put(warehouse, sharded);
            if ("WH-A".equals(warehouse)) {
                cellA = sharded;
                jdbcA = new JdbcTemplate(physical);
            } else {
                cellB = sharded;
                jdbcB = new JdbcTemplate(physical);
            }
        }
        routed = new WarehouseRoutingDataSource();
        ((WarehouseRoutingDataSource) routed).setTargetDataSources(cells);
        ((WarehouseRoutingDataSource) routed).setLenientFallback(false);
        ((WarehouseRoutingDataSource) routed).afterPropertiesSet();
        assertThrows(IllegalStateException.class, routed::getConnection);
        fence = InventoryTccFence.bind(routed);
        Configuration config = new Configuration(
                new Environment("shard", new SpringManagedTransactionFactory(), routed));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(WarehouseRouteMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        template = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(routed));
        inventory = new InventoryApplicationService(new SqlSessionTemplate(sessions), clock);
        action = new ReservationTccAction(inventory);
        seed("WH-A", "LOC-A", "GATE-A");
        seed("WH-B", "LOC-B", "GATE-B");
        receive("WH-A", "LOC-A", "SKU-A", "10");
        receive("WH-B", "LOC-B", "SKU-B", "10");
    }

    @AfterAll
    static void cleanup() throws Exception {
        closeQuietly(cellA);
        closeQuietly(cellB);
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void fenceAndReservationStayOnRoutedPhysicalShard() throws Exception {
        BusinessActionContext tryA = context("xid-a", 11L, "WH-A", "ALLOC-A", "ATT-A");
        BusinessActionContextUtil.setContext(tryA);
        try {
            assertEquals(true, fence.prepareFence(tryA.getXid(), tryA.getBranchId(), ReservationTccAction.ACTION_NAME,
                    () -> action.tryReserve(tryA, "ENT-1", "WH-A", "ALLOC-A", "ATT-A", DIGEST, 1L,
                            List.of(line("WH-A", "LOC-A", "SKU-A", "4", "OL-A")))));
        } finally {
            BusinessActionContextUtil.clear();
        }
        assertEquals(0, reserved(jdbcA, "SKU-A").compareTo(new BigDecimal("4.000000")));
        assertEquals(0, reserved(jdbcB, "SKU-B").compareTo(new BigDecimal("0.000000")));
        assertEquals(1, jdbcA.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-a'", Integer.class));
        assertEquals(0, jdbcB.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-a'", Integer.class));

        assertThrows(RuntimeException.class, () -> {
            BusinessActionContext failB = context("xid-fail", 12L, "WH-B", "ALLOC-F", "ATT-F");
            BusinessActionContextUtil.setContext(failB);
            try {
                fence.prepareFence(failB.getXid(), failB.getBranchId(), ReservationTccAction.ACTION_NAME, () -> {
                    action.tryReserve(failB, "ENT-1", "WH-B", "ALLOC-F", "ATT-F", DIGEST, 1L,
                            List.of(line("WH-B", "LOC-B", "SKU-B", "3", "OL-F")));
                    throw new IllegalStateException("注入Try失败");
                });
            } finally {
                BusinessActionContextUtil.clear();
            }
        });
        assertEquals(0, reserved(jdbcB, "SKU-B").compareTo(new BigDecimal("0.000000")));
        assertEquals(0, jdbcB.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-fail'", Integer.class));
        assertEquals(0, jdbcA.queryForObject("SELECT COUNT(*) FROM reservation WHERE allocation_id='ALLOC-F'",
                Integer.class));

        var confirm = ReservationTccAction.class.getMethod("confirmReserve", BusinessActionContext.class);
        var cancel = ReservationTccAction.class.getMethod("cancelReserve", BusinessActionContext.class);
        BusinessActionContext persisted = context("xid-a", 11L, "WH-A", "ALLOC-A", "ATT-A");
        BusinessActionContextUtil.setContext(persisted);
        try {
            assertTrue(fence.commitFence(confirm, action, persisted.getXid(), persisted.getBranchId(),
                    new Object[]{persisted}));
            assertTrue(fence.commitFence(confirm, action, persisted.getXid(), persisted.getBranchId(),
                    new Object[]{persisted}));
        } finally {
            BusinessActionContextUtil.clear();
        }
        assertEquals(ReservationState.CONFIRMED, jdbcA.queryForObject(
                "SELECT state FROM reservation WHERE allocation_id='ALLOC-A'", String.class));
        assertEquals(0, reserved(jdbcA, "SKU-A").compareTo(new BigDecimal("4.000000")));
        assertEquals(2, jdbcA.queryForObject("SELECT status FROM tcc_fence_log WHERE xid='xid-a'", Integer.class));
        assertNull(jdbcA.queryForObject(
                "SELECT execution_authorization_id FROM reservation WHERE allocation_id='ALLOC-A'", String.class));

        BusinessActionContext tryB = context("xid-b", 14L, "WH-B", "ALLOC-B", "ATT-B");
        BusinessActionContextUtil.setContext(tryB);
        try {
            assertEquals(true, fence.prepareFence(tryB.getXid(), tryB.getBranchId(), ReservationTccAction.ACTION_NAME,
                    () -> action.tryReserve(tryB, "ENT-1", "WH-B", "ALLOC-B", "ATT-B", DIGEST, 1L,
                            List.of(line("WH-B", "LOC-B", "SKU-B", "2", "OL-B")))));
            assertTrue(fence.rollbackFence(cancel, action, tryB.getXid(), tryB.getBranchId(), new Object[]{tryB},
                    ReservationTccAction.ACTION_NAME));
        } finally {
            BusinessActionContextUtil.clear();
        }
        assertEquals(ReservationState.CANCELLED, jdbcB.queryForObject(
                "SELECT state FROM reservation WHERE allocation_id='ALLOC-B'", String.class));
        assertEquals(0, reserved(jdbcB, "SKU-B").compareTo(new BigDecimal("0.000000")));
        assertEquals(ReservationState.CONFIRMED, jdbcA.queryForObject(
                "SELECT state FROM reservation WHERE allocation_id='ALLOC-A'", String.class));
        assertEquals(0, reserved(jdbcA, "SKU-A").compareTo(new BigDecimal("4.000000")));

        BusinessActionContextUtil.setContext(context("xid-owner", 21L, "WH-A", "ALLOC-A", "ATT-A"));
        try {
            template.executeWithoutResult(status -> {
                InventoryException mismatch = assertThrows(InventoryException.class,
                        () -> inventory.cancelTried("ENT-1", "WH-A", "OP-FOREIGN", "ALLOC-A",
                                ReservationTccAction.ACTION_NAME, "ALLOC-A", "ATT-A", "foreign-xid", 99L,
                                ReservationTccAction.ACTION_NAME));
                assertEquals("OWNER_MISMATCH", mismatch.code());
            });
        } finally {
            BusinessActionContextUtil.clear();
        }
        assertEquals(ReservationState.CONFIRMED, jdbcA.queryForObject(
                "SELECT state FROM reservation WHERE allocation_id='ALLOC-A'", String.class));
        assertEquals(0, reserved(jdbcA, "SKU-A").compareTo(new BigDecimal("4.000000")));

        BusinessActionContext empty = context("xid-empty", 13L, "WH-B", "ALLOC-E", "ATT-E");
        BusinessActionContextUtil.setContext(empty);
        try {
            assertTrue(fence.rollbackFence(cancel, action, empty.getXid(), empty.getBranchId(), new Object[]{empty},
                    ReservationTccAction.ACTION_NAME));
            assertThrows(RuntimeException.class, () -> fence.prepareFence("xid-empty", 13L,
                    ReservationTccAction.ACTION_NAME, () -> fail("空回滚后不能执行晚Try")));
        } finally {
            BusinessActionContextUtil.clear();
        }
        assertEquals(4, jdbcB.queryForObject("SELECT status FROM tcc_fence_log WHERE xid='xid-empty'", Integer.class));
        assertEquals(0, reserved(jdbcB, "SKU-B").compareTo(new BigDecimal("0.000000")));

        assertThrows(Exception.class, () -> jdbcA.queryForList("SELECT * FROM inv_b.stock_balance"));
        assertThrows(Exception.class, () -> jdbcB.update("UPDATE inv_a.stock_balance SET reserved_qty=0"));
        System.out.println("S4_FENCE_SHARD: routed physical cells; Fence+reservation same TX; "
                + "phase-two uses persisted warehouse context; empty rollback rejects late Try");
    }

    private static void seed(String warehouseId, String locationId, String gateId) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        BusinessActionContextUtil.setContext(context("seed-" + warehouseId, 1L, warehouseId, "SEED", "SEED"));
        try {
            template.executeWithoutResult(status -> {
                Configuration config = new Configuration(
                        new Environment("seed", new SpringManagedTransactionFactory(), routed));
                config.addMapper(MasterdataMapper.class);
                var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
                MasterdataService masterdata = new MasterdataService(session, clock);
                masterdata.createWarehouse(warehouseId, "ENT-1", warehouseId, warehouseId + "仓", "Asia/Shanghai");
                masterdata.createLocation(locationId, gateId, "ENT-1", warehouseId, locationId, "A", "STORAGE",
                        new BigDecimal("100"), "EA");
            });
        } finally {
            BusinessActionContextUtil.clear();
        }
    }

    private static void receive(String warehouseId, String locationId, String skuId, String qty) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        BusinessActionContextUtil.setContext(context("rcv-" + skuId, 2L, warehouseId, "RCV", "RCV"));
        try {
            template.executeWithoutResult(status -> {
                Configuration config = new Configuration(
                        new Environment("rcv", new SpringManagedTransactionFactory(), routed));
                config.addMapper(MasterdataMapper.class);
                config.addMapper(InventoryMapper.class);
                config.addMapper(OutboxMapper.class);
                config.addMapper(CommandDedupMapper.class);
                new InventoryApplicationService(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)), clock)
                        .receive("ENT-1", warehouseId, "OP-RCV-" + skuId, "DOC", "ACTOR",
                                bucket(warehouseId, locationId, skuId), Quantity.parse(qty, 0));
            });
        } finally {
            BusinessActionContextUtil.clear();
        }
    }

    private static ReservationLineInput line(String warehouseId, String locationId, String skuId, String qty,
            String orderLineId) {
        return new ReservationLineInput(bucket(warehouseId, locationId, skuId), Quantity.parse(qty, 0), orderLineId);
    }

    private static StockBucketKey bucket(String warehouseId, String locationId, String skuId) {
        return StockBucketKey.of("ENT-1", warehouseId, "OWNER-1", locationId, skuId, MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static BusinessActionContext context(String xid, long branchId, String warehouseId, String allocationId,
            String attemptId) {
        var context = new BusinessActionContext();
        context.setXid(xid);
        context.setBranchId(branchId);
        context.setActionName(ReservationTccAction.ACTION_NAME);
        context.setActionContext(new HashMap<>());
        context.addActionContext("enterpriseId", "ENT-1");
        context.addActionContext("warehouseId", warehouseId);
        context.addActionContext("allocationId", allocationId);
        context.addActionContext("attemptId", attemptId);
        return context;
    }

    private static BigDecimal reserved(JdbcTemplate jdbc, String skuId) {
        return jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id=?", BigDecimal.class, skuId);
    }

    private static DataSource source(String database, String user) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database
                + "?allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUser(user);
        ds.setPassword(mysql.getPassword());
        return ds;
    }

    private static String yaml(String warehouse) {
        StringBuilder tables = new StringBuilder();
        for (String table : List.of("warehouse", "location", "location_gate", "warehouse_route", "stock_balance",
                "stock_ledger", "reservation", "reservation_line", "outbox_event", "command_dedup")) {
            tables.append("      ").append(table).append(":\n        actualDataNodes: cell.").append(table)
                    .append("\n        databaseStrategy:\n          standard:\n            shardingColumn: warehouse_id\n")
                    .append("            shardingAlgorithmName: cell_route\n");
        }
        tables.append("      tcc_fence_log:\n        actualDataNodes: cell.tcc_fence_log\n        databaseStrategy:\n")
                .append("          standard:\n            shardingColumn: xid\n            shardingAlgorithmName: cell_route\n");
        return "databaseName: cell_" + warehouse + "\nmode:\n  type: Standalone\n  repository:\n    type: Memory\n"
                + "rules:\n  - !SHARDING\n    tables:\n" + tables
                + "    shardingAlgorithms:\n      cell_route:\n        type: CLASS_BASED\n        props:\n"
                + "          strategy: STANDARD\n          algorithmClassName: "
                + InventoryCellRouteAlgorithm.class.getName() + "\n          warehouse: " + warehouse + "\n"
                + "props:\n  sql-show: false\n";
    }

    private static void closeQuietly(DataSource dataSource) throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    /** Fence 开事务前按持久化仓上下文选物理库，禁止无上下文回落到默认仓。 */
    static final class WarehouseRoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            var context = BusinessActionContextUtil.getContext();
            if (context == null) {
                throw new IllegalStateException("缺少TCC仓路由上下文");
            }
            Object warehouse = context.getActionContext("warehouseId");
            if (!("WH-A".equals(warehouse) || "WH-B".equals(warehouse))) {
                throw new IllegalStateException("TCC仓路由不匹配");
            }
            return warehouse;
        }
    }
}
