package com.lrj.wms.inbound.receipt.persistence;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 入库仓任务按类型读取与领取；SQL 只在 XML。 */
public interface InboundTaskMapper {
    /** 读取{@code inbound_task}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> listTasks(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskType") String taskType,
            @Param("cursor") String cursor,
            @Param("limit") int limit);

    /** 读取{@code inbound_task}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getTask(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);

    /** 读取{@code inbound_task}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockTask(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);

    /** 写入{@code inbound_task}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int claimTask(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("epoch") long epoch,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);
}
