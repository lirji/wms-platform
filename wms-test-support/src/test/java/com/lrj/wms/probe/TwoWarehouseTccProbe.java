package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.common.Constants;
import org.apache.seata.integration.tx.api.util.JsonUtil;
import org.apache.seata.core.model.BranchType;
import org.apache.seata.integration.tx.api.fence.DefaultCommonFenceHandler;
import org.apache.seata.rm.DefaultResourceManager;
import org.apache.seata.rm.RMClient;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.apache.seata.rm.tcc.TCCResource;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 单RM进程注册两个仓资源的真实TC组合探针；不冒充两个独立RM进程或正式业务。 */
final class TwoWarehouseTccProbe {
    private static final String RESOURCE_PREFIX = "s0-warehouse-";
    private final Map<String, DataSource> physical = new HashMap<>();
    private final SpringFenceHandler fence = new SpringFenceHandler();
    private final AtomicBoolean rejectB = new AtomicBoolean(true);
    private final SqlSessionTemplate sessions;

    TwoWarehouseTccProbe(MySQLContainer mysql) throws Exception {
        var admin = new JdbcTemplate(source(mysql, "seata", "root"));
        for (String warehouse : new String[]{"A", "B"}) {
            String database = "s0_tcc_" + warehouse;
            admin.execute("CREATE DATABASE " + database);
            admin.execute("CREATE USER '" + database + "'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
            admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON " + database + ".* TO '" + database + "'@'%'");
            Flyway.configure().dataSource(source(mysql, database, "root"))
                    .locations("classpath:db/probe", "classpath:db/tcc-warehouse").load().migrate();
            var ds = source(mysql, database, database);
            physical.put(warehouse, ds);
            new JdbcTemplate(ds).update("INSERT INTO stock_probe VALUES (?, 'sku',100,0,0)", warehouse);
        }
        var routed = new ContextDataSource();
        routed.setTargetDataSources(new HashMap<>(physical));
        routed.setLenientFallback(false);
        routed.afterPropertiesSet();
        SpringFenceHandler.setDataSource(routed);
        SpringFenceHandler.setTransactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(routed)));
        DefaultCommonFenceHandler.get().setFenceHandler(fence);
        var configuration = new Configuration(new Environment("tcc-probe", new SpringManagedTransactionFactory(), routed));
        configuration.addMapper(StockProbeMapper.class);
        configuration.addMapper(TccProbeMapper.class);
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        RMClient.init("wms-s0-db-probe", "wms_s0_group");
        for (String warehouse : physical.keySet()) {
            var resource = new TCCResource();
            resource.setActionName(RESOURCE_PREFIX + warehouse);
            resource.setResourceGroupId("s0");
            resource.setAppName("wms-s0-db-probe");
            var callback = new Callback(this, warehouse);
            resource.setTargetBean(callback);
            resource.setPrepareMethod(Callback.class.getMethod("prepare", BusinessActionContext.class));
            resource.setCommitMethod(Callback.class.getMethod("confirm", BusinessActionContext.class));
            resource.setRollbackMethod(Callback.class.getMethod("cancel", BusinessActionContext.class));
            resource.setCommitArgsClasses(new Class<?>[]{BusinessActionContext.class});
            resource.setRollbackArgsClasses(new Class<?>[]{BusinessActionContext.class});
            resource.setPhaseTwoCommitKeys(new String[]{"context"});
            resource.setPhaseTwoRollbackKeys(new String[]{"context"});
            DefaultResourceManager.get().registerResource(resource);
        }
        // 无上下文不能静默落到默认仓；这一约束直接在取连接时执行，不依赖会吞异常的TccHook。
        assertThrows(IllegalStateException.class, routed::getConnection);
    }

    /** 第一仓确认、第二仓失败期间禁止产生TC成功证据，恢复后两仓各落一次效果。 */
    void verify(JdbcTemplate tcDatabase, org.testcontainers.containers.GenericContainer<?> tc) throws Exception {
        var transaction = GlobalTransactionContext.createNew();
        transaction.begin(30000, "s0-two-warehouse-commit");
        String xid = transaction.getXid();
        prepare(xid, "A");
        prepare(xid, "B");
        transaction.commit();
        await(() -> effectCount("A", xid, "CONFIRM") == 1);
        assertEquals(0, effectCount("B", xid, "CONFIRM"));
        assertEquals(0, tcDatabase.queryForObject("SELECT COUNT(*) FROM terminal_evidence WHERE xid=?", Integer.class, xid));
        assertEquals(1, new JdbcTemplate(physical.get("B")).queryForObject(
                "SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND status=1", Integer.class, xid));
        // 在一仓已确认、另一仓待重试时重启本测试TC，证明回调路由可从持久化上下文恢复。
        tc.getDockerClient().restartContainerCmd(tc.getContainerId()).exec();
        org.testcontainers.containers.wait.strategy.Wait.forListeningPort()
                .withStartupTimeout(Duration.ofSeconds(45)).waitUntilReady(tc);
        rejectB.set(false);
        // 原生客户端首次重连任务在init后60秒触发；保留默认行为并测量，不伪造立即重连。
        await(Duration.ofSeconds(90), () -> effectCount("B", xid, "CONFIRM") == 1);
        await(() -> tcDatabase.queryForObject("SELECT COUNT(*) FROM terminal_evidence WHERE xid=? AND terminal_status=9", Integer.class, xid) == 1);
        for (String warehouse : physical.keySet()) {
            assertEquals(30L, reserved(warehouse));
            assertEquals(1, effectCount(warehouse, xid, "CONFIRM"));
            assertEquals(1, new JdbcTemplate(physical.get(warehouse)).queryForObject(
                    "SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND status=2", Integer.class, xid));
        }
        var rollback = GlobalTransactionContext.createNew();
        rollback.begin(30000, "s0-two-warehouse-rollback");
        String rollbackXid = rollback.getXid();
        prepare(rollbackXid, "A");
        prepare(rollbackXid, "B");
        rollback.rollback();
        await(() -> effectCount("A", rollbackXid, "CANCEL") == 1 && effectCount("B", rollbackXid, "CANCEL") == 1);
        for (String warehouse : physical.keySet()) {
            assertEquals(30L, reserved(warehouse));
            assertEquals(1, new JdbcTemplate(physical.get(warehouse)).queryForObject(
                    "SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND status=3", Integer.class, rollbackXid));
        }
        assertNull(BusinessActionContextUtil.getContext());
        System.out.println("TWO_WAREHOUSE_PROBE: real TC callbacks use per-warehouse physical transaction; B failure rolls back effect and fence; recovery and two-branch cancel pass");
    }

    private void prepare(String xid, String warehouse) throws Exception {
        String resource = RESOURCE_PREFIX + warehouse;
        String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT,
                Map.of("warehouse", warehouse, Constants.USE_COMMON_FENCE, true)));
        long branch = DefaultResourceManager.get().branchRegister(BranchType.TCC, resource, null, xid, data, null);
        var context = BusinessActionContextUtil.getBusinessActionContext(xid, branch, resource, data);
        BusinessActionContextUtil.setContext(context);
        try {
            fence.prepareFence(xid, branch, resource, () -> {
                assertEquals(1, sessions.getMapper(StockProbeMapper.class).reserve(warehouse, "sku", 30));
                return true;
            });
        } finally { BusinessActionContextUtil.clear(); }
    }

    /** 二阶段上下文由TC持久化回传，在Fence开启事务之前决定唯一物理数据源。 */
    private static final class ContextDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            var context = BusinessActionContextUtil.getContext();
            if (context == null) throw new IllegalStateException("缺少TCC仓路由上下文");
            Object warehouse = context.getActionContext("warehouse");
            if (!("A".equals(warehouse) || "B".equals(warehouse))
                    || !(RESOURCE_PREFIX + warehouse).equals(context.getActionName())) {
                throw new IllegalStateException("TCC资源与仓路由不匹配");
            }
            return warehouse;
        }
    }

    /** TC反射调用的业务对象；持久化效果和Fence均使用相同路由DataSource。 */
    public static final class Callback {
        private final TwoWarehouseTccProbe probe;
        private final String warehouse;
        Callback(TwoWarehouseTccProbe probe, String warehouse) { this.probe = probe; this.warehouse = warehouse; }
        /** prepare由测试显式调用Fence，保留方法用于原生资源注册元数据。 */
        public boolean prepare(BusinessActionContext context) { throw new UnsupportedOperationException("探针显式prepare"); }
        /** 先写效果后注入异常，验证失败事务没有留下部分业务效果。 */
        public boolean confirm(BusinessActionContext context) {
            probe.sessions.getMapper(TccProbeMapper.class).effect(context.getXid(), context.getBranchId(), "CONFIRM");
            if ("B".equals(warehouse) && probe.rejectB.get()) throw new IllegalStateException("s0 injected B confirm failure");
            return true;
        }
        /** 取消仅释放本次Try数量，原有已确认占用应保持不变。 */
        public boolean cancel(BusinessActionContext context) {
            var mapper = probe.sessions.getMapper(TccProbeMapper.class);
            assertEquals(1, mapper.effect(context.getXid(), context.getBranchId(), "CANCEL"));
            assertEquals(1, mapper.release(warehouse, 30));
            return true;
        }
    }

    private int effectCount(String warehouse, String xid, String phase) {
        return new JdbcTemplate(physical.get(warehouse)).queryForObject(
                "SELECT COUNT(*) FROM callback_effect WHERE xid=? AND phase=?", Integer.class, xid, phase);
    }
    private long reserved(String warehouse) {
        return new JdbcTemplate(physical.get(warehouse)).queryForObject("SELECT reserved FROM stock_probe WHERE warehouse_id=?", Long.class, warehouse);
    }
    private static DataSource source(MySQLContainer mysql, String database, String user) {
        var source = new MysqlDataSource();
        source.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false");
        source.setUser(user);
        source.setPassword(mysql.getPassword());
        return source;
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        await(Duration.ofSeconds(30), condition);
    }
    private static void await(Duration timeout, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < deadline);
        fail("两仓TC探针在" + timeout.toSeconds() + "秒内未收敛");
    }
}
