package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.common.Constants;
import org.apache.seata.core.model.BranchType;
import org.apache.seata.integration.tx.api.fence.DefaultCommonFenceHandler;
import org.apache.seata.integration.tx.api.util.JsonUtil;
import org.apache.seata.rm.DefaultResourceManager;
import org.apache.seata.rm.RMClient;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.apache.seata.rm.tcc.TCCResource;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 隔离测试RM进程，只能连接一个库存Cell；stdin控制协议不对外提供网络接口。 */
public final class WarehouseRmProcess {
    private final String warehouse = System.getenv("PROBE_WAREHOUSE");
    private final AtomicBoolean reject = new AtomicBoolean(Boolean.parseBoolean(System.getenv("PROBE_REJECT")));
    private final SpringFenceHandler fence = new SpringFenceHandler();
    private final SqlSessionTemplate sessions;

    private WarehouseRmProcess() throws Exception {
        if (!("A".equals(warehouse) || "B".equals(warehouse))) throw new IllegalArgumentException("非法仓");
        var physical = new MysqlDataSource();
        physical.setURL(System.getenv("PROBE_JDBC_URL"));
        physical.setUser(System.getenv("PROBE_DB_USER"));
        physical.setPassword(System.getenv("PROBE_DB_PASSWORD"));
        StringBuilder tables = new StringBuilder();
        for (String table : new String[]{"stock_probe", "tcc_fence_log", "callback_effect"}) {
            String key = table.equals("stock_probe") ? "warehouse_id" : "xid";
            tables.append("      ").append(table).append(":\n        actualDataNodes: cell.").append(table)
                    .append("\n        databaseStrategy:\n          standard:\n            shardingColumn: ").append(key)
                    .append("\n            shardingAlgorithmName: cell_route\n");
        }
        String yaml = "databaseName: rm_" + warehouse + "\nmode:\n  type: Standalone\n  repository:\n    type: Memory\n"
                + "rules:\n  - !SHARDING\n    tables:\n" + tables
                + "    shardingAlgorithms:\n      cell_route:\n        type: CLASS_BASED\n        props:\n"
                + "          strategy: STANDARD\n          algorithmClassName: com.lrj.wms.probe.CellFenceAlgorithm\n"
                + "          warehouse: " + warehouse + "\nprops:\n  sql-show: false\n";
        var sharded = YamlShardingSphereDataSourceFactory.createDataSource(Map.of("cell", physical), yaml.getBytes(StandardCharsets.UTF_8));
        SpringFenceHandler.setDataSource(sharded);
        SpringFenceHandler.setTransactionTemplate(new TransactionTemplate(new DataSourceTransactionManager(sharded)));
        DefaultCommonFenceHandler.get().setFenceHandler(fence);
        var configuration = new Configuration(new Environment("process", new SpringManagedTransactionFactory(), sharded));
        configuration.addMapper(StockProbeMapper.class);
        configuration.addMapper(TccProbeMapper.class);
        sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        System.setProperty("service.vgroupMapping.wms_s0_group", "default");
        System.setProperty("service.default.grouplist", System.getenv("PROBE_TC_ADDRESS"));
        RMClient.init("wms-s0-process-" + warehouse, "wms_s0_group");
        var resource = new TCCResource();
        resource.setActionName("process-" + warehouse);
        resource.setResourceGroupId("s0");
        resource.setAppName("wms-s0-process-" + warehouse);
        resource.setTargetBean(this);
        resource.setPrepareMethod(getClass().getMethod("prepare", String.class));
        resource.setCommitMethod(getClass().getMethod("confirm", BusinessActionContext.class));
        resource.setRollbackMethod(getClass().getMethod("cancel", BusinessActionContext.class));
        resource.setCommitArgsClasses(new Class<?>[]{BusinessActionContext.class});
        resource.setRollbackArgsClasses(new Class<?>[]{BusinessActionContext.class});
        resource.setPhaseTwoCommitKeys(new String[]{"context"});
        resource.setPhaseTwoRollbackKeys(new String[]{"context"});
        DefaultResourceManager.get().registerResource(resource);
    }

    /** 仅供测试父进程传入XID；正式Try入口与幂等仍需单独实现。 */
    public long prepare(String xid) {
        try {
            String resource = "process-" + warehouse;
            String data = JsonUtil.toJSONString(Map.of(Constants.TX_ACTION_CONTEXT,
                    Map.of("warehouse", warehouse, Constants.USE_COMMON_FENCE, true)));
            long branch = DefaultResourceManager.get().branchRegister(BranchType.TCC, resource, null, xid, data, null);
            BusinessActionContextUtil.setContext(BusinessActionContextUtil.getBusinessActionContext(xid, branch, resource, data));
            fence.prepareFence(xid, branch, resource, () -> {
                if (sessions.getMapper(StockProbeMapper.class).reserve(warehouse, "sku", 30) != 1) {
                    throw new InsufficientStock();
                }
                return true;
            });
            return branch;
        } catch (Exception failure) { throw new IllegalStateException("探针Try失败", failure); }
        finally { BusinessActionContextUtil.clear(); }
    }

    /** 效果插入后故障应同时回滚效果与Fence，且全部经过ShardingSphere。 */
    public boolean confirm(BusinessActionContext context) {
        sessions.getMapper(TccProbeMapper.class).effect(context.getXid(), context.getBranchId(), "CONFIRM");
        if (reject.get()) throw new IllegalStateException("s0 process confirm failure");
        return true;
    }
    /** 只释放本探针本次预占；生产所有权与数量规则另有业务实现门禁。 */
    public boolean cancel(BusinessActionContext context) {
        var mapper = sessions.getMapper(TccProbeMapper.class);
        if (mapper.effect(context.getXid(), context.getBranchId(), "CANCEL") != 1
                || mapper.release(warehouse, 30) != 1) throw new IllegalStateException("取消数量不一致");
        return true;
    }
    /** 区分确定的库存不足与未知系统失败，测试协议不能把任意异常伪装成拒绝。 */
    private static final class InsufficientStock extends RuntimeException {
        private InsufficientStock() { super("探针库存不足"); }
    }
    private static boolean isInsufficient(Throwable failure) {
        for (int depth = 0; failure != null && depth < 10; depth++, failure = failure.getCause()) {
            if (failure instanceof InsufficientStock) return true;
        }
        return false;
    }

    /** 通过父子进程管道控制，不把测试故障开关添加到业务服务。 */
    public static void main(String[] args) throws Exception {
        var participant = new WarehouseRmProcess();
        System.out.println("WMS_READY");
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.startsWith("PREPARE ")) {
                    try { System.out.println("WMS_RESULT " + participant.prepare(line.substring(8))); }
                    catch (RuntimeException failure) {
                        if (isInsufficient(failure)) System.out.println("WMS_REJECTED INSUFFICIENT");
                        else throw failure;
                    }
                }
                else throw new IllegalArgumentException("未知测试命令");
            }
        }
        System.exit(0);
    }
}
