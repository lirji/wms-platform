package com.lrj.wms.inventory.tcc;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
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
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.common.Constants;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.BranchType;
import org.apache.seata.integration.tx.api.util.JsonUtil;
import org.apache.seata.rm.DefaultResourceManager;
import org.apache.seata.rm.RMClient;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.apache.seata.rm.tcc.TCCResource;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S4-04：真实 TC 驱动 ReservationTccAction 的 Confirm/Cancel/重试。不是 fulfillment 屏障或 XXL 二阶段。
 */
class SeataTccRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-11T16:00:00Z");
    private static final String DIGEST = "d".repeat(64);
    private static final String ACTION = ReservationTccAction.ACTION_NAME;
    private static final String GROUP = "wms_inventory_group";

    @Test
    void realTcConfirmsCancelsAndRetriesWithoutRecompetingStock() throws Exception {
        System.setProperty("config.type", "file");
        System.setProperty("config.file.name", "file.conf");
        System.setProperty("registry.type", "file");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            MysqlDataSource source = new MysqlDataSource();
            source.setUrl(mysql.getJdbcUrl());
            source.setUser(mysql.getUsername());
            source.setPassword(mysql.getPassword());
            DataSource dataSource = source;
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            SpringFenceHandler fence = InventoryTccFence.bind(dataSource);
            Configuration config = new Configuration(
                    new Environment("tcc", new SpringManagedTransactionFactory(), dataSource));
            config.addMapper(MasterdataMapper.class);
            config.addMapper(InventoryMapper.class);
            config.addMapper(OutboxMapper.class);
            config.addMapper(CommandDedupMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(config);
            var tx = new DataSourceTransactionManager(dataSource);
            var template = new TransactionTemplate(tx);
            template.executeWithoutResult(status -> {
                var session = new SqlSessionTemplate(sessions);
                MasterdataService masterdata = new MasterdataService(session, clock);
                masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
                masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                        new BigDecimal("100"), "EA");
            });
            InventoryApplicationService inventory = new InventoryApplicationService(new SqlSessionTemplate(sessions), clock);
            ReservationTccAction action = new ReservationTccAction(inventory);
            RecoveringAction original = new RecoveringAction(action, 0);
            StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-TC",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            template.executeWithoutResult(status -> inventory.receive("ENT-1", "WH-A", "OP-RCV-TC", "DOC", "ACTOR", bucket,
                    Quantity.parse("10", 0)));

            try (var tc = seata(port)) {
                tc.start();
                System.setProperty("config.type", "file");
                System.setProperty("config.file.name", "file.conf");
                System.setProperty("registry.type", "file");
                System.setProperty("service.vgroupMapping." + GROUP, "default");
                System.setProperty("service.default.grouplist", "127.0.0.1:" + port);
                TMClient.init("wms-inventory-s4", GROUP);
                RMClient.init("wms-inventory-s4", GROUP);
                TCCResource resource = register(original);

                var commitTx = GlobalTransactionContext.createNew();
                commitTx.begin(30000, "s4-confirm");
                String commitXid = commitTx.getXid();
                tryReserve(fence, action, commitXid, "ALLOC-C", "ATT-C", bucket, "4", "OL-C");
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("4.000000")));
                RecoveringAction swapped = new RecoveringAction(action, 1);
                resource.setTargetBean(swapped);
                jdbc.update("UPDATE location_gate SET state='FROZEN' WHERE location_id='LOC-1'");
                try {
                    commitTx.commit();
                    await(() -> ReservationState.CONFIRMED.equals(state(jdbc, "ALLOC-C")), Duration.ofSeconds(30));
                } finally {
                    jdbc.update("UPDATE location_gate SET state='OPEN' WHERE location_id='LOC-1'");
                }
                assertEquals(ReservationState.CONFIRMED, state(jdbc, "ALLOC-C"));
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("4.000000")));
                assertEquals(2, fenceStatus(jdbc, commitXid));
                assertEquals(0, original.confirmAttempts.get());
                assertTrue(swapped.confirmAttempts.get() >= 2, "换实例后的 Confirm 应重试至少两次");
                assertNull(jdbc.queryForObject(
                        "SELECT execution_authorization_id FROM reservation WHERE allocation_id='ALLOC-C'", String.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'", Integer.class));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event WHERE event_type LIKE '%ALLOCATED%'", Integer.class));
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                                + "AND table_name IN ('fulfillment','execution_authorization','outbound_order')",
                        Integer.class));

                var rollbackTx = GlobalTransactionContext.createNew();
                rollbackTx.begin(30000, "s4-cancel");
                String rollbackXid = rollbackTx.getXid();
                tryReserve(fence, action, rollbackXid, "ALLOC-X", "ATT-X", bucket, "3", "OL-X");
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("7.000000")));
                rollbackTx.rollback();
                await(() -> ReservationState.CANCELLED.equals(state(jdbc, "ALLOC-X")), Duration.ofSeconds(30));
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("4.000000")));
                assertEquals(3, fenceStatus(jdbc, rollbackXid));

                var timeoutTx = GlobalTransactionContext.createNew();
                timeoutTx.begin(8000, "s4-timeout");
                String timeoutXid = timeoutTx.getXid();
                tryReserve(fence, action, timeoutXid, "ALLOC-T", "ATT-T", bucket, "2", "OL-T");
                assertEquals(ReservationState.TRIED, state(jdbc, "ALLOC-T"));
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("6.000000")));
                Thread.sleep(400);
                assertEquals(ReservationState.TRIED, state(jdbc, "ALLOC-T"), "XXL 不存在，TRIED 在 TC 超时前不得自行释放");
                RootContext.unbind();
                await(() -> ReservationState.CANCELLED.equals(state(jdbc, "ALLOC-T")), Duration.ofSeconds(45));
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("4.000000")));
                assertEquals(3, fenceStatus(jdbc, timeoutXid));

                var emptyTx = GlobalTransactionContext.createNew();
                emptyTx.begin(30000, "s4-empty");
                String emptyXid = emptyTx.getXid();
                emptyTx.rollback();
                assertNotNull(emptyXid);
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE allocation_id='ALLOC-E'",
                        Integer.class));
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("4.000000")));

                template.executeWithoutResult(status -> {
                    InventoryException cancelMismatch = assertThrows(InventoryException.class,
                            () -> inventory.cancelTried("ENT-1", "WH-A", "OP-FOREIGN", "ALLOC-C", ACTION, "ALLOC-C",
                                    "ATT-C", "foreign-xid", 99L, ACTION));
                    assertEquals("OWNER_MISMATCH", cancelMismatch.code());
                    InventoryException confirmMismatch = assertThrows(InventoryException.class,
                            () -> inventory.confirmTried("ENT-1", "WH-A", "OP-FOREIGN-C", "ALLOC-C", ACTION, "ALLOC-C",
                                    "ATT-C", "foreign-xid", 99L, ACTION));
                    assertEquals("OWNER_MISMATCH", confirmMismatch.code());
                });
                assertEquals(ReservationState.CONFIRMED, state(jdbc, "ALLOC-C"));
                assertEquals(0, reserved(jdbc).compareTo(new BigDecimal("4.000000")));
                RootContext.unbind();
                System.out.println("S4_TC_RECOVERY: real file-mode TC Confirm/Cancel/timeout on ReservationTccAction; "
                        + "phase-two swapped instance; Confirm does not recompete; missing terminal evidence is not "
                        + "ALLOCATED/execution permit; not XXL cluster or fulfillment barrier");
            } finally {
                RootContext.unbind();
            }
        }
    }

    private static TCCResource register(RecoveringAction recovering) throws Exception {
        var resource = new TCCResource();
        resource.setActionName(ACTION);
        resource.setResourceGroupId("s4");
        resource.setAppName("wms-inventory-s4");
        resource.setTargetBean(recovering);
        resource.setPrepareMethod(RecoveringAction.class.getMethod("prepare", BusinessActionContext.class));
        resource.setCommitMethod(RecoveringAction.class.getMethod("confirmReserve", BusinessActionContext.class));
        resource.setRollbackMethod(RecoveringAction.class.getMethod("cancelReserve", BusinessActionContext.class));
        resource.setCommitArgsClasses(new Class<?>[]{BusinessActionContext.class});
        resource.setRollbackArgsClasses(new Class<?>[]{BusinessActionContext.class});
        resource.setPhaseTwoCommitKeys(new String[]{"context"});
        resource.setPhaseTwoRollbackKeys(new String[]{"context"});
        DefaultResourceManager.get().registerResource(resource);
        return resource;
    }

    private static void tryReserve(SpringFenceHandler fence, ReservationTccAction action, String xid, String allocationId,
            String attemptId, StockBucketKey bucket, String qty, String orderLineId) throws Exception {
        String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT, Map.of(
                Constants.USE_COMMON_FENCE, true,
                "enterpriseId", "ENT-1",
                "warehouseId", "WH-A",
                "allocationId", allocationId,
                "attemptId", attemptId,
                "requestDigest", DIGEST,
                "routeEpoch", 1L)));
        long branch = DefaultResourceManager.get().branchRegister(BranchType.TCC, ACTION, null, xid, data, null);
        var context = BusinessActionContextUtil.getBusinessActionContext(xid, branch, ACTION, data);
        if (context.getActionContext() == null) {
            context.setActionContext(new java.util.HashMap<>());
        }
        BusinessActionContextUtil.setContext(context);
        try {
            fence.prepareFence(xid, branch, ACTION, () -> action.tryReserve(context, "ENT-1", "WH-A", allocationId,
                    attemptId, DIGEST, 1L, List.of(new ReservationLineInput(bucket, Quantity.parse(qty, 0), orderLineId))));
        } finally {
            BusinessActionContextUtil.clear();
        }
    }

    private static GenericContainer<?> seata(int tcPort) {
        return new GenericContainer<>("apache/seata-server:2.6.0")
                .withEnv("SEATA_IP", "127.0.0.1").withEnv("SEATA_PORT", Integer.toString(tcPort))
                .withEnv("STORE_MODE", "file").withEnv("JAVA_OPTS", "-Xms128m -Xmx256m")
                .withExposedPorts(tcPort)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", tcPort), new ExposedPort(tcPort))))
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3));
    }

    private static BigDecimal reserved(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-TC'", BigDecimal.class);
    }

    private static String state(JdbcTemplate jdbc, String allocationId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT state FROM reservation WHERE allocation_id=?",
                allocationId);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("state"));
    }

    private static int fenceStatus(JdbcTemplate jdbc, String xid) {
        return jdbc.queryForObject("SELECT status FROM tcc_fence_log WHERE xid=?", Integer.class, xid);
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("真实TC回调在" + timeout.toSeconds() + "秒内未收敛");
    }

    /** TC 反射回调；Confirm 先成功写库再注入一次失败，验证 Fence 同事务回滚后由 TC 重试。 */
    public static final class RecoveringAction {
        private final ReservationTccAction delegate;
        private final AtomicInteger confirmAttempts = new AtomicInteger();
        private final AtomicInteger remainingFailures;

        RecoveringAction(ReservationTccAction delegate, int injectedConfirmFailures) {
            this.delegate = delegate;
            this.remainingFailures = new AtomicInteger(injectedConfirmFailures);
        }

        public boolean prepare(BusinessActionContext context) {
            throw new UnsupportedOperationException("S4-04 显式 Try");
        }

        public boolean confirmReserve(BusinessActionContext context) {
            confirmAttempts.incrementAndGet();
            boolean result = delegate.confirmReserve(context);
            if (remainingFailures.getAndDecrement() > 0) {
                throw new IllegalStateException("注入Confirm失败供TC重试");
            }
            return result;
        }

        public boolean cancelReserve(BusinessActionContext context) {
            return delegate.cancelReserve(context);
        }
    }
}
