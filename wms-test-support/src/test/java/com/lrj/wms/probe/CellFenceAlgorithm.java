package com.lrj.wms.probe;

import java.util.Collection;
import java.util.Properties;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

/** 每个RM绑定单Cell，ShardingSphere不得将Fence或库存SQL路由到其他Cell。 */
public final class CellFenceAlgorithm implements StandardShardingAlgorithm<String> {
    private String warehouse;
    /** 初始化服务端绑定的仓，不能由HTTP参数覆盖。 */
    @Override public void init(Properties properties) { warehouse = properties.getProperty("warehouse"); }
    /** 库存SQL核对仓字段，Fence/效果SQL核对XID，所有表统一访问本Cell。 */
    @Override public String doSharding(Collection<String> targets, PreciseShardingValue<String> value) {
        var context = BusinessActionContextUtil.getContext();
        if (context == null || !warehouse.equals(context.getActionContext("warehouse"))
                || !("process-" + warehouse).equals(context.getActionName())) {
            throw new IllegalStateException("缺少或不匹配的Cell事务上下文");
        }
        String expected = "warehouse_id".equals(value.getColumnName()) ? warehouse : context.getXid();
        if (!expected.equals(value.getValue()) || targets.size() != 1 || !targets.contains("cell")) {
            throw new IllegalStateException("SQL路由键与TCC上下文不一致");
        }
        return "cell";
    }
    /** 禁止在这条事务写路径使用范围路由。 */
    @Override public Collection<String> doSharding(Collection<String> targets, RangeShardingValue<String> value) {
        throw new IllegalArgumentException("Cell事务不允许范围路由");
    }
    /** 类算法仅供隔离探针装配。 */
    @Override public String getType() { return "CELL_FENCE_PROBE"; }
}
