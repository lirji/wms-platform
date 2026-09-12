package com.lrj.wms.inbound.receipt;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 入库仓任务按类型读取与领取；SQL 只在 XML。 */
public interface InboundTaskMapper {
    List<Map<String, Object>> listTasks(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskType") String taskType, @Param("cursor") String cursor,
            @Param("limit") int limit);

    Map<String, Object> getTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);

    Map<String, Object> lockTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);

    int claimTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId, @Param("workerId") String workerId, @Param("epoch") long epoch,
            @Param("expectedVersion") long expectedVersion, @Param("now") Timestamp now);
}
