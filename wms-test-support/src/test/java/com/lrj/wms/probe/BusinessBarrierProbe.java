package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.xxl.job.core.handler.IJobHandler;
import java.time.Duration;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 把真实terminal_evidence接到attempt/XID/epoch/参与者Fence。
 * 缺证据不得写ALLOCATED；XXL不得Confirm/Cancel。不是正式wms-fulfillment服务。
 */
final class BusinessBarrierProbe {
    private static final String RESOURCE_PREFIX = "s0-barrier-";
    private static final String TENANT = "enterprise";
    private static final String WAREHOUSE = "A";
    private static final String GROUP = "wms_s0_group";
    private static final String PROBE_TM = "wms-s0-db-probe";
    private static final long EPOCH = 1L;

    private final SpringFenceHandler fence = new SpringFenceHandler();
    private final SqlSessionTemplate sessions;
    private final JdbcTemplate business;
    private final JdbcTemplate audit;
    private final TerminalEvidenceAdapter adapter;

    BusinessBarrierProbe(MySQLContainer mysql) throws Exception {
        var admin = new JdbcTemplate(source(mysql, "seata", "root"));
        admin.execute("CREATE DATABASE s0_barrier");
        admin.execute("CREATE USER 's0_barrier'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON s0_barrier.* TO 's0_barrier'@'%'");
        admin.execute("CREATE USER 'wms_tc_audit'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
        admin.execute("GRANT SELECT ON seata.terminal_evidence TO 'wms_tc_audit'@'%'");
        Flyway.configure().dataSource(source(mysql, "s0_barrier", "root"))
                .locations("classpath:db/probe", "classpath:db/tcc-warehouse", "classpath:db/barrier-probe")
                .load().migrate();
        var ds = source(mysql, "s0_barrier", "s0_barrier");
        business = new JdbcTemplate(ds);
        business.update("INSERT INTO stock_probe VALUES (?, 'sku',100,0,0)", WAREHOUSE);
        audit = new JdbcTemplate(source(mysql, "seata", "wms_tc_audit"));
        SpringFenceHandler.setDataSource(ds);
        SpringFenceHandler.setTransactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(ds)));
        DefaultCommonFenceHandler.get().setFenceHandler(fence);
        var configuration = new Configuration(new Environment("barrier", new SpringManagedTransactionFactory(), ds));
        configuration.addMapper(StockProbeMapper.class);
        configuration.addMapper(TccProbeMapper.class);
        configuration.addMapper(BusinessBarrierMapper.class);
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        adapter = new TerminalEvidenceAdapter(audit, business, attempts());
        RMClient.init(PROBE_TM, GROUP);
        var resource = new TCCResource();
        resource.setActionName(resource(WAREHOUSE));
        resource.setResourceGroupId("s0");
        resource.setAppName(PROBE_TM);
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

    /** 绑定不可变attempt后发起Try；提交前不得放行。 */
    void tryAllocate(String attempt, String xid) throws Exception {
        bindAttempt(attempt, xid, PROBE_TM, WAREHOUSE);
        prepare(xid, WAREHOUSE);
    }

    /** 按当前代际评估屏障；缺证据必须是RECOVERY_PENDING。 */
    TerminalEvidenceAdapter.Decision decision(String attempt) {
        return adapter.evaluate(TENANT, attempt, EPOCH);
    }

    /** 仅ALLOW时写Outbox；其他决定必须保持零记录。 */
    int release(String attempt) {
        return adapter.releaseIfAllowed(TENANT, attempt, EPOCH);
    }

    /** 该attempt的ALLOCATED Outbox行数。 */
    int outbox(String attempt) {
        return attempts().outboxCount(TENANT, attempt);
    }

    /** XXL执行线程禁止发起Confirm/Cancel。 */
    void refuseXxlPhaseTwo() {
        adapter.refusePhaseTwoFromJob();
    }

    /** 真实提交才ALLOW；缺证据/错代际/空参与者/错TM为RECOVERY_PENDING；回滚DENIED；XXL禁二阶段。 */
    void verify(JdbcTemplate tcDatabase) throws Exception {
        adapter.assertAuditIsReadOnly();
        String commitAttempt = "barrier-commit";
        var committed = GlobalTransactionContext.createNew();
        committed.begin(60000, "s0-barrier-commit");
        String commitXid = committed.getXid();
        bindAttempt(commitAttempt, commitXid, PROBE_TM, WAREHOUSE);
        prepare(commitXid, WAREHOUSE);
        assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING,
                adapter.evaluate(TENANT, commitAttempt, EPOCH), "提交前缺终态证据不得放行");
        assertEquals(0, adapter.releaseIfAllowed(TENANT, commitAttempt, EPOCH));
        committed.commit();
        await(() -> fenceStatus(commitXid) == 2);
        await(() -> Integer.valueOf(9).equals(evidenceStatus(tcDatabase, commitXid)));
        assertEquals(PROBE_TM, audit.queryForObject(
                "SELECT application_id FROM terminal_evidence WHERE xid=?", String.class, commitXid));
        assertEquals(TerminalEvidenceAdapter.Decision.ALLOW_ALLOCATED,
                adapter.evaluate(TENANT, commitAttempt, EPOCH));
        assertEquals(1, adapter.releaseIfAllowed(TENANT, commitAttempt, EPOCH));
        assertEquals(1, attempts().outboxCount(TENANT, commitAttempt));
        assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING,
                adapter.evaluate(TENANT, commitAttempt, EPOCH + 1), "代际不匹配不得放行");

        String missingAttempt = "barrier-missing";
        attempts().insertAttempt(TENANT, missingAttempt, EPOCH, "missing-barrier-xid", PROBE_TM, GROUP, WAREHOUSE);
        attempts().insertParticipant(TENANT, missingAttempt, WAREHOUSE, 1);
        assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING,
                adapter.evaluate(TENANT, missingAttempt, EPOCH));
        assertEquals(0, adapter.releaseIfAllowed(TENANT, missingAttempt, EPOCH));

        String emptyAttempt = "barrier-empty";
        attempts().insertAttempt(TENANT, emptyAttempt, EPOCH, commitXid + "-empty", PROBE_TM, GROUP, "");
        assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING,
                adapter.evaluate(TENANT, emptyAttempt, EPOCH), "空参与者清单不得ALLOW");

        String wrongTmAttempt = "barrier-wrong-tm";
        var wrongTm = GlobalTransactionContext.createNew();
        wrongTm.begin(60000, "s0-barrier-wrong-tm");
        String wrongTmXid = wrongTm.getXid();
        bindAttempt(wrongTmAttempt, wrongTmXid, TerminalEvidenceAdapter.PRODUCTION_TM, WAREHOUSE);
        prepare(wrongTmXid, WAREHOUSE);
        wrongTm.commit();
        await(() -> fenceStatus(wrongTmXid) == 2);
        await(() -> Integer.valueOf(9).equals(evidenceStatus(tcDatabase, wrongTmXid)));
        assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING,
                adapter.evaluate(TENANT, wrongTmAttempt, EPOCH), "生产TM身份与证据不匹配不得放行");
        assertEquals(0, adapter.releaseIfAllowed(TENANT, wrongTmAttempt, EPOCH));

        String rollbackAttempt = "barrier-rollback";
        var rolledBack = GlobalTransactionContext.createNew();
        rolledBack.begin(60000, "s0-barrier-rollback");
        String rollbackXid = rolledBack.getXid();
        bindAttempt(rollbackAttempt, rollbackXid, PROBE_TM, WAREHOUSE);
        prepare(rollbackXid, WAREHOUSE);
        rolledBack.rollback();
        await(() -> fenceStatus(rollbackXid) == 3);
        await(() -> Integer.valueOf(11).equals(evidenceStatus(tcDatabase, rollbackXid)));
        assertEquals(TerminalEvidenceAdapter.Decision.DENIED,
                adapter.evaluate(TENANT, rollbackAttempt, EPOCH));
        assertEquals(0, adapter.releaseIfAllowed(TENANT, rollbackAttempt, EPOCH));

        RootContext.bind("xxl-barrier-must-clear");
        IJobHandler handler = new IJobHandler() {
            @Override
            public void execute() {
                RootContext.unbind();
                assertNull(RootContext.getXID());
                assertNull(GlobalTransactionContext.getCurrent());
                assertThrows(IllegalStateException.class, adapter::refusePhaseTwoFromJob);
            }
        };
        try {
            handler.execute();
        } finally {
            RootContext.unbind();
        }
        assertNull(GlobalTransactionContext.getCurrent());
        System.out.println("BUSINESS_BARRIER_PROBE: read-only audit + attempt/XID/epoch/participants; "
                + "ALLOW only after committed evidence; missing/mismatch=RECOVERY_PENDING; rollback=DENIED; XXL cannot drive phase two");
    }

    static String resource(String warehouse) {
        return RESOURCE_PREFIX + warehouse;
    }

    private void bindAttempt(String attempt, String xid, String expectedTm, String warehouse) {
        assertEquals(1, attempts().insertAttempt(TENANT, attempt, EPOCH, xid, expectedTm, GROUP, warehouse));
        assertEquals(1, attempts().insertParticipant(TENANT, attempt, warehouse, 1));
    }

    private void prepare(String xid, String warehouse) throws Exception {
        String resource = resource(warehouse);
        String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT,
                Map.of("warehouse", warehouse, Constants.USE_COMMON_FENCE, true)));
        long branch = DefaultResourceManager.get().branchRegister(BranchType.TCC, resource, null, xid, data, null);
        var context = BusinessActionContextUtil.getBusinessActionContext(xid, branch, resource, data);
        BusinessActionContextUtil.setContext(context);
        try {
            assertEquals(Boolean.TRUE, fence.prepareFence(xid, branch, resource, () -> {
                assertEquals(1, sessions.getMapper(StockProbeMapper.class).reserve(warehouse, "sku", 30));
                return true;
            }));
        } finally {
            BusinessActionContextUtil.clear();
        }
    }

    /** TC反射回调；Confirm不写业务放行，放行只走屏障。 */
    public static final class Callback {
        private final BusinessBarrierProbe probe;
        Callback(BusinessBarrierProbe probe) { this.probe = probe; }
        public boolean prepare(BusinessActionContext context) { throw new UnsupportedOperationException("屏障探针禁止直接prepare"); }
        public boolean confirm(BusinessActionContext context) { return true; }
        public boolean cancel(BusinessActionContext context) {
            assertEquals(1, probe.sessions.getMapper(TccProbeMapper.class).release(WAREHOUSE, 30));
            return true;
        }
    }

    private BusinessBarrierMapper attempts() { return sessions.getMapper(BusinessBarrierMapper.class); }
    private int fenceStatus(String xid) {
        var statuses = business.queryForList("SELECT status FROM tcc_fence_log WHERE xid=?", Integer.class, xid);
        return statuses.isEmpty() ? 0 : statuses.getFirst();
    }
    private static Integer evidenceStatus(JdbcTemplate tcDatabase, String xid) {
        var statuses = tcDatabase.queryForList("SELECT terminal_status FROM terminal_evidence WHERE xid=?", Integer.class, xid);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < deadline);
        fail("业务屏障探针在30秒内未收敛");
    }
    private static DataSource source(MySQLContainer mysql, String database, String user) {
        var source = new MysqlDataSource();
        source.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database
                + "?allowPublicKeyRetrieval=true&useSSL=false");
        source.setUser(user);
        source.setPassword(mysql.getPassword());
        return source;
    }
}
