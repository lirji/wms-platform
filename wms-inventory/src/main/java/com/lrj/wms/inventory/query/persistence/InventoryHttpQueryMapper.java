package com.lrj.wms.inventory.query.persistence;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 库存流水、操作与效果只读；SQL 只在 XML。 */
public interface InventoryHttpQueryMapper {
    /** 读取{@code stock_balance}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getBalance(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId);

    /** 读取{@code stock_ledger}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> listLedger(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("balanceId") String balanceId,
            @Param("cursor") String cursor,
            @Param("limit") int limit);

    /** 读取{@code stock_ledger}，将 SQL 与绑定参数保持在同一持久化入口。 */
    List<Map<String, Object>> listLedgerByOperation(
            @Param("enterpriseId") String enterpriseId,
            @Param("operationId") String operationId,
            @Param("warehouseIds") List<String> warehouseIds);

    /** 读取{@code stock_effect}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> listEffects(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId,
            @Param("cursor") String cursor,
            @Param("limit") int limit);
}
