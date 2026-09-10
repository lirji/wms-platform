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
import org.apache.seata.integration.http.jakarta.JakartaTransactionPropagationInterceptor;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 代表已确认 TM=`wms-fulfillment` 的 HTTP Try：先绑定 XID，禁止盲目重放 prepareFence。不是正式履约服务。 */
final class HttpGatewayTryProbe {
    private static final String RESOURCE = "s0-http-try";
    private static final String TENANT = "enterprise";
    private static final String WAREHOUSE = "A";
    private static final String ALLOCATION = "allocation";
    private static final String ATTEMPT = "http-attempt-1";
    private static final String DIGEST = "qty:30:EA";
    private static final String TM = "wms-fulfillment";

    private final SpringFenceHandler fence = new SpringFenceHandler();
    private final SqlSessionTemplate sessions;
    private final JdbcTemplate stock;
    private final AtomicInteger businessTries = new AtomicInteger();
    private final MockMvc mvc;

    HttpGatewayTryProbe(MySQLContainer mysql) throws Exception {
        var admin = new JdbcTemplate(source(mysql, "seata", "root"));
        admin.execute("CREATE DATABASE s0_http");
        admin.execute("CREATE USER 's0_http'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
        admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON s0_http.* TO 's0_http'@'%'");
        Flyway.configure().dataSource(source(mysql, "s0_http", "root"))
                .locations("classpath:db/probe", "classpath:db/tcc-warehouse", "classpath:db/retry-probe").load().migrate();
        var ds = source(mysql, "s0_http", "s0_http");
        stock = new JdbcTemplate(ds);
        stock.update("INSERT INTO stock_probe VALUES (?, 'sku',100,0,0)", WAREHOUSE);
        SpringFenceHandler.setDataSource(ds);
        SpringFenceHandler.setTransactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(ds)));
        DefaultCommonFenceHandler.get().setFenceHandler(fence);
        var configuration = new Configuration(new Environment("http", new SpringManagedTransactionFactory(), ds));
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
        mvc = MockMvcBuilders.standaloneSetup(new TryApi(this))
                .addInterceptors(new JakartaTransactionPropagationInterceptor())
                .build();
    }

    /** HTTP 重试必须走网关所有权恢复，不得再 branchRegister/prepareFence。 */
    void verify(JdbcTemplate tcDatabase) throws Exception {
        var transaction = GlobalTransactionContext.createNew();
        transaction.begin(60000, "s0-http-gateway-try");
        String xid = transaction.getXid();
        mvc.perform(post("/internal/tcc/try").header(RootContext.KEY_XID, xid).header("X-Wms-Tm", TM))
                .andExpect(status().isOk()).andExpect(content().string("TRY_OK"));
        assertEquals(30L, reserved());
        assertEquals(1, businessTries.get());
        assertEquals(1, tcDatabase.queryForObject("SELECT COUNT(*) FROM branch_table WHERE xid=?", Integer.class, xid));
        Long branch = reservations().ownerBranch(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT);
        mvc.perform(post("/internal/tcc/try").header(RootContext.KEY_XID, xid).header("X-Wms-Tm", TM))
                .andExpect(status().isOk()).andExpect(content().string("TRY_OK"));
        assertEquals(30L, reserved());
        assertEquals(1, businessTries.get(), "HTTP重试不得再次执行Try业务");
        assertEquals(1, tcDatabase.queryForObject("SELECT COUNT(*) FROM branch_table WHERE xid=?", Integer.class, xid));
        assertEquals(branch, reservations().ownerBranch(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT));
        mvc.perform(post("/internal/tcc/try").header("X-Wms-Tm", TM))
                .andExpect(status().isBadRequest()).andExpect(content().string("XID_REQUIRED"));
        mvc.perform(post("/internal/tcc/try").header(RootContext.KEY_XID, xid).header("X-Wms-Tm", "other-tm"))
                .andExpect(status().isForbidden()).andExpect(content().string("TM_FORBIDDEN"));
        RootContext.bind(xid);
        transaction.rollback();
        await(() -> reserved() == 0L);
        System.out.println("HTTP_GATEWAY_TRY_PROBE: interceptor bound XID; retry reused owner without new branch; missing XID rejected");
    }

    private ResponseEntity<String> handleTry() throws Exception {
        String xid = RootContext.getXID();
        if (xid == null || xid.isBlank()) return ResponseEntity.badRequest().body("XID_REQUIRED");
        String existing = reservations().ownerXid(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT);
        if (xid.equals(existing)) {
            if (!DIGEST.equals(reservations().digest(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT))) {
                return ResponseEntity.status(409).body("TCC_CONTEXT_MISMATCH");
            }
            return ResponseEntity.ok("TRY_OK");
        }
        if (existing != null) return ResponseEntity.status(409).body("TCC_OWNER_CONFLICT");
        String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT,
                Map.of("warehouse", WAREHOUSE, Constants.USE_COMMON_FENCE, true)));
        long branch = DefaultResourceManager.get().branchRegister(BranchType.TCC, RESOURCE, null, xid, data, null);
        var context = BusinessActionContextUtil.getBusinessActionContext(xid, branch, RESOURCE, data);
        BusinessActionContextUtil.setContext(context);
        try {
            Object prepared = fence.prepareFence(xid, branch, RESOURCE, () -> {
                businessTries.incrementAndGet();
                try {
                    assertEquals(1, reservations().insert(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT, xid, branch, RESOURCE, DIGEST, 30));
                } catch (DuplicateKeyException duplicate) {
                    throw new IllegalStateException("TCC_OWNER_CONFLICT");
                }
                assertEquals(1, sessions.getMapper(StockProbeMapper.class).reserve(WAREHOUSE, "sku", 30));
                return true;
            });
            assertEquals(Boolean.TRUE, prepared);
            return ResponseEntity.ok("TRY_OK");
        } finally {
            BusinessActionContextUtil.clear();
        }
    }

    @RestController
    static final class TryApi {
        private final HttpGatewayTryProbe probe;
        TryApi(HttpGatewayTryProbe probe) { this.probe = probe; }

        /** 仅接受已确认 TM；XID 由 Seata HTTP 拦截器从请求头绑定。 */
        @PostMapping("/internal/tcc/try")
        ResponseEntity<String> tryReserve(@RequestHeader(value = "X-Wms-Tm", required = false) String tm) throws Exception {
            if (!TM.equals(tm)) return ResponseEntity.status(403).body("TM_FORBIDDEN");
            return probe.handleTry();
        }
    }

    /** TC反射回调；HTTP探针Cancel只释放本所有者。 */
    public static final class Callback {
        private final HttpGatewayTryProbe probe;
        Callback(HttpGatewayTryProbe probe) { this.probe = probe; }
        public boolean prepare(BusinessActionContext context) { throw new UnsupportedOperationException("HTTP探针禁止直接prepare"); }
        public boolean confirm(BusinessActionContext context) { return true; }
        public boolean cancel(BusinessActionContext context) {
            int owned = probe.reservations().deleteOwner(TENANT, WAREHOUSE, ALLOCATION, ATTEMPT,
                    context.getXid(), context.getBranchId(), RESOURCE);
            if (owned != 1) return true;
            assertEquals(1, probe.sessions.getMapper(TccProbeMapper.class).release(WAREHOUSE, 30));
            return true;
        }
    }

    private ReservationProbeMapper reservations() { return sessions.getMapper(ReservationProbeMapper.class); }
    private long reserved() { return stock.queryForObject("SELECT reserved FROM stock_probe WHERE warehouse_id=?", Long.class, WAREHOUSE); }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < deadline);
        fail("HTTP Try探针在30秒内未收敛");
    }
    private static DataSource source(MySQLContainer mysql, String database, String user) {
        var source = new MysqlDataSource();
        source.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false");
        source.setUser(user);
        source.setPassword(mysql.getPassword());
        return source;
    }
}
