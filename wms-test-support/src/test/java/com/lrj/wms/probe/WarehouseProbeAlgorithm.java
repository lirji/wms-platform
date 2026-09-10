package com.lrj.wms.probe;

import java.util.Collection;
import java.util.Properties;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

/** 隔离POC使用固定两仓目录，未知仓或范围条件拒绝路由，防止广播写入。 */
public final class WarehouseProbeAlgorithm implements StandardShardingAlgorithm<String> {
    /** 精确仓映射必须命中已有数据源，不允许未知仓落到默认库。 */
    @Override
    public String doSharding(Collection<String> targets, PreciseShardingValue<String> value) {
        String target = switch (value.getValue()) {
            case "A" -> "ds_a";
            case "B" -> "ds_b";
            default -> throw new IllegalArgumentException("未知探针仓库");
        };
        if (!targets.contains(target)) throw new IllegalStateException("探针数据源缺失");
        return target;
    }

    /** 库存写入拒绝范围分片，避免误广播到多个事务资源。 */
    @Override
    public Collection<String> doSharding(Collection<String> targets, RangeShardingValue<String> value) {
        throw new IllegalArgumentException("禁止通过仓库范围广播库存写入");
    }

    /** 本探针使用固定目录，不接受动态配置覆盖仓库归属。 */
    @Override
    public void init(Properties properties) { }

    /** 提供与其他项目算法隔离的SPI类型名称。 */
    @Override
    public String getType() { return "WMS_S0_WAREHOUSE"; }
}
