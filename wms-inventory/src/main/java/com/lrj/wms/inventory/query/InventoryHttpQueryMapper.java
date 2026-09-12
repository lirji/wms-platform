package com.lrj.wms.inventory.query;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存流水、操作与效果只读；SQL 只在 XML。 */
public interface InventoryHttpQueryMapper {
    Map<String, Object> getBalance(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId);

    List<Map<String, Object>> listLedger(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("balanceId") String balanceId,
            @Param("cursor") String cursor, @Param("limit") int limit);

    List<Map<String, Object>> listLedgerByOperation(@Param("enterpriseId") String enterpriseId,
            @Param("operationId") String operationId, @Param("warehouseIds") List<String> warehouseIds);

    List<Map<String, Object>> listEffects(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskId") String taskId, @Param("cursor") String cursor,
            @Param("limit") int limit);
}
