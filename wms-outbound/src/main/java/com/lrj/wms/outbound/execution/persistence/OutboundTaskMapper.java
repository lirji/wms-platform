package com.lrj.wms.outbound.execution.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 出库仓任务按类型读取；SQL 只在 XML。领取沿用 OutboundOrderMapper.claimTask。 */
public interface OutboundTaskMapper {
    /** 读取{@code outbound_task}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> listTasks(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskType") String taskType,
            @Param("cursor") String cursor,
            @Param("limit") int limit);

    /** 读取{@code outbound_task}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getTask(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);
}
