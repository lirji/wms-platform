package com.lrj.wms.inventory.tcc;

import java.util.Collection;
import java.util.Properties;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

/** 单 Cell 分片：Fence/库存 SQL 必须带着匹配的仓上下文，禁止广播到另一物理库。 */
public final class InventoryCellRouteAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {
    private String warehouse;

    @Override
    public void init(Properties properties) {
        warehouse = properties.getProperty("warehouse");
    }

    @Override
    public String doSharding(Collection<String> targets, PreciseShardingValue<Comparable<?>> value) {
        var context = BusinessActionContextUtil.getContext();
        if (context == null || !warehouse.equals(String.valueOf(context.getActionContext("warehouseId")))) {
            throw new IllegalStateException("缺少或不匹配的仓路由上下文");
        }
        if ("xid".equals(value.getColumnName()) && !context.getXid().equals(String.valueOf(value.getValue()))) {
            throw new IllegalStateException("Fence XID 与上下文不一致");
        }
        if ("warehouse_id".equals(value.getColumnName()) && !warehouse.equals(String.valueOf(value.getValue()))) {
            throw new IllegalStateException("仓库键与绑定 Cell 不一致");
        }
        if (targets.size() != 1 || !targets.contains("cell")) {
            throw new IllegalStateException("Cell 分片不能广播");
        }
        return "cell";
    }

    @Override
    public Collection<String> doSharding(Collection<String> targets, RangeShardingValue<Comparable<?>> value) {
        throw new IllegalArgumentException("Cell 事务不允许范围路由");
    }

    @Override
    public String getType() {
        return "INVENTORY_CELL_ROUTE";
    }
}
