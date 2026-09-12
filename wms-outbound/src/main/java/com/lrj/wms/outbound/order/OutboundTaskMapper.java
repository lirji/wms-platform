package com.lrj.wms.outbound.order;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 出库仓任务按类型读取；SQL 只在 XML。领取沿用 OutboundOrderMapper.claimTask。 */
public interface OutboundTaskMapper {
    List<Map<String, Object>> listTasks(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskType") String taskType, @Param("cursor") String cursor,
            @Param("limit") int limit);

    Map<String, Object> getTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId);
}
