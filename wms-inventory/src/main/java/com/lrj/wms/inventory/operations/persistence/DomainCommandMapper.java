package com.lrj.wms.inventory.operations.persistence;

import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** 移库、限制与独立调整单据；SQL 只在 XML。 */
public interface DomainCommandMapper {
    /** 写入{@code warehouse_move}，将 SQL 与绑定参数保持在同一持久化入口。唯一约束吸收重试，影响行数用于区分首次写入和重复。 */
    int insertMoveIgnore(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId,
            @Param("sourceBalanceId") String sourceBalanceId,
            @Param("targetLocationId") String targetLocationId,
            @Param("targetBalanceId") String targetBalanceId,
            @Param("qty") BigDecimal qty,
            @Param("unit") String unit,
            @Param("reason") String reason,
            @Param("actorId") String actorId,
            @Param("operationId") String operationId,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 读取{@code warehouse_move}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getMoveByKey(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    /** 写入{@code stock_hold}，将 SQL 与绑定参数保持在同一持久化入口。唯一约束吸收重试，影响行数用于区分首次写入和重复。 */
    int insertHoldIgnore(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId,
            @Param("balanceId") String balanceId,
            @Param("locationId") String locationId,
            @Param("skuId") String skuId,
            @Param("lotId") String lotId,
            @Param("qty") BigDecimal qty,
            @Param("reason") String reason,
            @Param("evidenceRefs") String evidenceRefs,
            @Param("actorId") String actorId,
            @Param("operationId") String operationId,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 读取{@code stock_hold}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getHoldByKey(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    /** 读取{@code stock_hold}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockHold(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("holdId") String holdId);

    /** 写入{@code stock_hold}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int casReleaseHold(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("holdId") String holdId,
            @Param("expectedVersion") long expectedVersion,
            @Param("releasedBy") String releasedBy,
            @Param("releaseReason") String releaseReason,
            @Param("releaseOperationId") String releaseOperationId,
            @Param("now") Timestamp now);

    /** 写入{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。唯一约束吸收重试，影响行数用于区分首次写入和重复。 */
    int insertAdjustmentIgnore(
            @Param("id") String id,
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId,
            @Param("countLineId") String countLineId,
            @Param("balanceId") String balanceId,
            @Param("deltaQty") BigDecimal deltaQty,
            @Param("reason") String reason,
            @Param("evidenceRefs") String evidenceRefs,
            @Param("actorId") String actorId,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 读取{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getAdjustmentByKey(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    /** 读取{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。行锁由调用方事务持有，读取和后续决策必须在同一事务内。 */
    Map<String, Object> lockAdjustment(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId);

    /** 读取{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。 */
    Map<String, Object> getAdjustment(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId);

    /** 读取{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。使用既定分页或批量上限，避免一次读取无界数据。 */
    List<Map<String, Object>> listAdjustments(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("cursor") String cursor,
            @Param("limit") int limit);

    /** 写入{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int casAdjustmentDecision(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId,
            @Param("expectedVersion") long expectedVersion,
            @Param("decision") String decision,
            @Param("decidedBy") String decidedBy,
            @Param("decisionReason") String decisionReason,
            @Param("state") String state,
            @Param("now") Timestamp now);

    /** 写入{@code warehouse_adjustment}，将 SQL 与绑定参数保持在同一持久化入口。返回实际影响行数，调用方据此识别条件不匹配。 */
    int casAdjustmentApplied(
            @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId,
            @Param("expectedVersion") long expectedVersion,
            @Param("applyOperationId") String applyOperationId,
            @Param("now") Timestamp now);
}
