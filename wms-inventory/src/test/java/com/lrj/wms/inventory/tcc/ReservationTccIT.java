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
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.rm.datasource.DataSourceProxy;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S4-03：ReservationTccAction + 官方 Fence 同库本地事务。不是真实 TC 二阶段恢复。 */
class ReservationTccIT {
    private static final Instant NOW = Instant.parse("2026-09-11T15:00:00Z");
    private static final String DIGEST = "c".repeat(64);

    private static MySQLContainer mysql;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SpringFenceHandler fence;
    private static ReservationTccAction action;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        dataSource = source;
        assertFalse(dataSource instanceof DataSourceProxy);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        fence = InventoryTccFence.bind(dataSource);
        Configuration config = new Configuration(
                new Environment("tcc", new SpringManagedTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        SqlSessionFactory sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        action = new ReservationTccAction(new InventoryApplicationService(new SqlSessionTemplate(sessions), clock));
        var tx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(status -> {
            var session = new SqlSessionTemplate(sessions);
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
        });
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void tryConfirmDoesNotRecompeteAndSharesFenceTransaction() throws Exception {
        receive("SKU-C", "10");
        StockBucketKey bucket = bucket("SKU-C");
        BusinessActionContext context = context("xid-confirm", 21L);
        assertEquals(true, fence.prepareFence(context.getXid(), context.getBranchId(), ReservationTccAction.ACTION_NAME,
                () -> action.tryReserve(context, "ENT-1", "WH-A", "ALLOC-C", "ATT-C", DIGEST, 1L,
                        List.of(new ReservationLineInput(bucket, Quantity.parse("4", 0), "OL-C")))));
        assertEquals(0, reserved("SKU-C").compareTo(new BigDecimal("4.000000")));
        jdbc.update("UPDATE location_gate SET state='FROZEN' WHERE location_id='LOC-1'");
        try {
            var confirm = ReservationTccAction.class.getMethod("confirmReserve", BusinessActionContext.class);
            assertTrue(fence.commitFence(confirm, action, context.getXid(), context.getBranchId(), new Object[]{context}));
            assertTrue(fence.commitFence(confirm, action, context.getXid(), context.getBranchId(), new Object[]{context}));
        } finally {
            jdbc.update("UPDATE location_gate SET state='OPEN' WHERE location_id='LOC-1'");
        }
        assertEquals(ReservationState.CONFIRMED,
                jdbc.queryForObject("SELECT state FROM reservation WHERE allocation_id='ALLOC-C'", String.class));
        assertEquals(0, reserved("SKU-C").compareTo(new BigDecimal("4.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-C'", BigDecimal.class)
                .compareTo(new BigDecimal("10.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT status FROM tcc_fence_log WHERE xid=? AND branch_id=?", Integer.class, "xid-confirm", 21L));
        Exception leftover = assertThrows(Exception.class, () -> {
            var ctx = context("xid-leftover", 22L);
            fence.prepareFence(ctx.getXid(), ctx.getBranchId(), ReservationTccAction.ACTION_NAME, () -> action.tryReserve(
                    ctx, "ENT-1", "WH-A", "ALLOC-L", "ATT-L", DIGEST, 1L,
                    List.of(new ReservationLineInput(bucket, Quantity.parse("7", 0), "OL-L"))));
        });
        assertEquals("STOCK_INSUFFICIENT", inventoryCode(leftover));
        assertEquals(0, reserved("SKU-C").compareTo(new BigDecimal("4.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-leftover'", Integer.class));
    }

    @Test
    void tryCancelReleasesAndEmptyRollbackIsSafe() throws Exception {
        receive("SKU-X", "8");
        StockBucketKey bucket = bucket("SKU-X");
        BusinessActionContext context = context("xid-cancel", 31L);
        assertEquals(true, fence.prepareFence(context.getXid(), context.getBranchId(), ReservationTccAction.ACTION_NAME,
                () -> action.tryReserve(context, "ENT-1", "WH-A", "ALLOC-X", "ATT-X", DIGEST, 1L,
                        List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-X")))));
        var cancel = ReservationTccAction.class.getMethod("cancelReserve", BusinessActionContext.class);
        assertTrue(fence.rollbackFence(cancel, action, context.getXid(), context.getBranchId(), new Object[]{context},
                ReservationTccAction.ACTION_NAME));
        assertTrue(fence.rollbackFence(cancel, action, context.getXid(), context.getBranchId(), new Object[]{context},
                ReservationTccAction.ACTION_NAME));
        assertEquals(ReservationState.CANCELLED,
                jdbc.queryForObject("SELECT state FROM reservation WHERE allocation_id='ALLOC-X'", String.class));
        assertEquals(0, reserved("SKU-X").compareTo(new BigDecimal("0.000000")));
        assertEquals(3, jdbc.queryForObject(
                "SELECT status FROM tcc_fence_log WHERE xid=? AND branch_id=?", Integer.class, "xid-cancel", 31L));

        BusinessActionContext empty = context("xid-empty", 32L);
        assertTrue(fence.rollbackFence(cancel, action, empty.getXid(), empty.getBranchId(), new Object[]{empty},
                ReservationTccAction.ACTION_NAME));
        assertEquals(4, jdbc.queryForObject(
                "SELECT status FROM tcc_fence_log WHERE xid=? AND branch_id=?", Integer.class, "xid-empty", 32L));
        assertThrows(RuntimeException.class, () -> fence.prepareFence("xid-empty", 32L, ReservationTccAction.ACTION_NAME,
                () -> fail("空回滚后不能执行Try业务")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE allocation_id='ALLOC-EMPTY'",
                Integer.class));
    }

    @Test
    void tryBusinessFailureRollsBackFenceAndStock() {
        receive("SKU-F", "5");
        StockBucketKey bucket = bucket("SKU-F");
        assertThrows(RuntimeException.class, () -> fence.prepareFence("xid-fail", 41L, ReservationTccAction.ACTION_NAME, () -> {
            action.tryReserve(context("xid-fail", 41L), "ENT-1", "WH-A", "ALLOC-F", "ATT-F", DIGEST, 1L,
                    List.of(new ReservationLineInput(bucket, Quantity.parse("2", 0), "OL-F")));
            throw new IllegalStateException("注入Try业务失败");
        }));
        assertEquals(0, reserved("SKU-F").compareTo(new BigDecimal("0.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-fail'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE allocation_id='ALLOC-F'", Integer.class));
    }

    private static void receive(String skuId, String qty) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Configuration config = new Configuration(
                new Environment("tcc", new SpringManagedTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        var sessions = new SqlSessionFactoryBuilder().build(config);
        var tx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(status -> {
            new InventoryApplicationService(new SqlSessionTemplate(sessions), clock).receive("ENT-1", "WH-A",
                    "OP-RCV-" + skuId + "-" + UUID.randomUUID(), "DOC", "ACTOR", bucket(skuId), Quantity.parse(qty, 0));
        });
    }

    private static StockBucketKey bucket(String skuId) {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", skuId, MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static BusinessActionContext context(String xid, long branchId) {
        var context = new BusinessActionContext();
        context.setXid(xid);
        context.setBranchId(branchId);
        context.setActionName(ReservationTccAction.ACTION_NAME);
        context.setActionContext(new java.util.HashMap<>());
        return context;
    }

    private static BigDecimal reserved(String skuId) {
        return jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id=?", BigDecimal.class, skuId);
    }

    private static String inventoryCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InventoryException inventory) {
                return inventory.code();
            }
        }
        throw new AssertionError("缺少库存错误码", error);
    }
}
