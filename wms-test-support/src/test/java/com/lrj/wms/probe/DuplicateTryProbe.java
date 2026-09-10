package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.common.Constants;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.core.model.BranchType;
import org.apache.seata.integration.tx.api.fence.DefaultCommonFenceHandler;
import org.apache.seata.integration.tx.api.util.JsonUtil;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实TC branchRegister观察重复Try会换branchId；业务键拒绝改绑。不是HTTP网关或正式预占服务。 */
final class DuplicateTryProbe {
    private static final String RESOURCE = "s0-retry";
    private static final String TENANT = "enterprise";
    private static final String WAREHOUSE = "A";
    private static final String ALLOCATION = "allocation";
    private static final String ATTEMPT = "attempt-1";
    private static final String DIGEST = "qty:30:EA";

    private final SpringFenceHandler fence = new SpringFenceHandler();
    private final SqlSessionTemplate sessions;
    private final JdbcTemplate stock;
    private final AtomicInteger businessTries = new AtomicInteger();

    DuplicateTryProbe(MySQLContainer mysql) throws Exception {
        var admin = new JdbcTemplate(source(mysql, "seata", "root"));
        admin.execute("CREATE DATABASE s0_retry");
        admin.execute("CREATE USER 's0_retry'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON s0_retry.* TO 's0_retry'@'%'");
        Flyway.configure().dataSource(source(mysql, "s0_retry", "root"))
                .locations("classpath:db/probe", "classpath:db/tcc-warehouse", "classpath:db/retry-probe").load().migrate();
        var ds = source(mysql, "s0_retry", "s0_retry");
        stock = new JdbcTemplate(ds);
        stock.update("INSERT INTO stock_probe VALUES (?, 'sku',100,0,0)", WAREHOUSE);
        SpringFenceHandler.setDataSource(ds);
        SpringFenceHandler.setTransactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(ds)));
        DefaultCommonFenceHandler.get().setFenceHandler(fence);
        var configuration = new Configuration(new Environment("retry", new SpringManagedTransactionFactory(), ds));
        configuration.addMapper(StockProbeMapper.class);
        configuration.addMapper(TccProbeMapper.class);
        configuration.addMapper(ReservationProbeMapper.class);
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        RMClient.init("wms-s0-db-probe", "wms_s0_group");
        var resource = new TCCResource();
        resource.setActionName(RESOURCE);
        resource.setResourceGroupId("s0");
        resource.setAppName("wms-s0-db-probe");
        var callback = new Callback(this);
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

    /** 原身份重放不得加库存；新branchId/新XID不得接管；失败分支Cancel不得释放原预占。 */
    void verify(JdbcTemplate tcDatabase) throws Exception {
        var transaction = GlobalTransactionContext.createNew();
        transaction.begin(60000, "s0-duplicate-try");
        String xid = transaction.getXid();
        long branch1 = register(xid);
        assertTrue(tryReserve(xid, branch1, DIGEST));
        assertEquals(30L, reserved());
        assertEquals(Long.valueOf(branch1), ownerBranch());
        assertEquals(1, businessTries.get());
        assertEquals(DIGEST, reservations().digest(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT));
        // 2.6对同xid/branch再次prepareFence会DuplicateKey，并异步清理原Tried记录；主路径禁止重放，否则后续Cancel会变成空回滚。
        long branch2 = register(xid);
        assertNotEquals(branch1, branch2, "真实branchRegister重试会得到新branchId，不能假设SDK复用原分支");
        assertEquals(2, tcDatabase.queryForObject("SELECT COUNT(*) FROM branch_table WHERE xid=?", Integer.class, xid));
        var ownerConflict = assertThrows(OwnerConflict.class, () -> tryReserve(xid, branch2, DIGEST));
        assertTrue(causedBy(ownerConflict, "TCC_OWNER_CONFLICT"));
        assertEquals(30L, reserved());
        assertEquals(Long.valueOf(branch1), ownerBranch());
        assertEquals(1, fenceRows(xid, branch1));
        assertEquals(0, fenceRows(xid, branch2));
        // 同一TM线程不能同时begin两个XID；外键冲突必须在原预占仍占用时验证，所以只解绑上下文、不结束原事务。
        RootContext.unbind();
        var foreign = GlobalTransactionContext.createNew();
        foreign.begin(60000, "s0-duplicate-foreign");
        String foreignXid = foreign.getXid();
        long branch3 = register(foreignXid);
        assertThrows(OwnerConflict.class, () -> tryReserve(foreignXid, branch3, DIGEST));
        assertEquals(30L, reserved());
        assertEquals(xid, reservations().ownerXid(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT));
        foreign.rollback();
        await(() -> stock.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND status=4", Integer.class, foreignXid) == 1);
        assertEquals(30L, reserved());
        assertEquals(Long.valueOf(branch1), ownerBranch());
        transaction.rollback();
        await(() -> reserved() == 0L && stock.queryForObject("SELECT COUNT(*) FROM reservation_probe", Integer.class) == 0);
        System.out.println("DUPLICATE_TRY_PROBE: branchRegister retry allocated new branchId; owner conflict rejected; foreign cancel left original reservation; original cancel released once");
    }

    private boolean tryReserve(String xid, long branch, String digest) {
        String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT,
                Map.of("warehouse", WAREHOUSE, Constants.USE_COMMON_FENCE, true)));
        var context = BusinessActionContextUtil.getBusinessActionContext(xid, branch, RESOURCE, data);
        BusinessActionContextUtil.setContext(context);
        try {
            // 2.6 Fence 回调返回 Object，不能按原始 boolean 直接作为方法结果。
            Object prepared = fence.prepareFence(xid, branch, RESOURCE, () -> {
                businessTries.incrementAndGet();
                try {
                    assertEquals(1, reservations().insert(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT, xid, branch, RESOURCE, digest, 30));
                } catch (DuplicateKeyException duplicate) {
                    if (xid.equals(reservations().ownerXid(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT))
                            && branch == reservations().ownerBranch(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT)) {
                        if (!digest.equals(reservations().digest(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT))) {
                            throw new IllegalStateException("TCC_CONTEXT_MISMATCH");
                        }
                        return true;
                    }
                    throw new IllegalStateException("TCC_OWNER_CONFLICT");
                }
                assertEquals(1, sessions.getMapper(StockProbeMapper.class).reserve(WAREHOUSE, "sku", 30));
                return true;
            });
            return Boolean.TRUE.equals(prepared);
        } catch (RuntimeException failure) {
            if (causedBy(failure, "TCC_OWNER_CONFLICT")) throw new OwnerConflict(failure);
            throw failure;
        } finally {
            BusinessActionContextUtil.clear();
        }
    }

    private long register(String xid) throws Exception {
        String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT,
                Map.of("warehouse", WAREHOUSE, Constants.USE_COMMON_FENCE, true)));
        return DefaultResourceManager.get().branchRegister(BranchType.TCC, RESOURCE, null, xid, data, null);
    }

    /** TC反射回调；非所有者Cancel必须直接返回，禁止释放他人预占。 */
    public static final class Callback {
        private final DuplicateTryProbe probe;
        Callback(DuplicateTryProbe probe) { this.probe = probe; }
        public boolean prepare(BusinessActionContext context) { throw new UnsupportedOperationException("探针显式prepare"); }
        public boolean confirm(BusinessActionContext context) {
            probe.sessions.getMapper(TccProbeMapper.class).effect(context.getXid(), context.getBranchId(), "CONFIRM");
            return true;
        }
        public boolean cancel(BusinessActionContext context) {
            int owned = probe.reservations().deleteOwner(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT,
                    context.getXid(), context.getBranchId(), RESOURCE);
            if (owned != 1) return true;
            var mapper = probe.sessions.getMapper(TccProbeMapper.class);
            assertEquals(1, mapper.effect(context.getXid(), context.getBranchId(), "CANCEL"));
            assertEquals(1, mapper.release(WAREHOUSE, 30));
            return true;
        }
    }

    private ReservationProbeMapper reservations() { return sessions.getMapper(ReservationProbeMapper.class); }
    private long reserved() { return stock.queryForObject("SELECT reserved FROM stock_probe WHERE warehouse_id=?", Long.class, WAREHOUSE); }
    private Long ownerBranch() { return reservations().ownerBranch(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT); }
    private int fenceRows(String xid, long branch) {
        return stock.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND branch_id=?", Integer.class, xid, branch);
    }
    private static boolean causedBy(Throwable failure, String token) {
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            if (failure.getMessage() != null && failure.getMessage().contains(token)) return true;
        }
        return false;
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < deadline);
        fail("重复Try探针在30秒内未收敛");
    }
    private static DataSource source(MySQLContainer mysql, String database, String user) {
        var source = new MysqlDataSource();
        source.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false");
        source.setUser(user);
        source.setPassword(mysql.getPassword());
        return source;
    }

    /** 新所有者冲突是预期业务拒绝，不能当成Fence或系统失败。 */
    static final class OwnerConflict extends RuntimeException {
        OwnerConflict(Throwable cause) { super("重复Try所有者冲突", cause); }
    }
}
