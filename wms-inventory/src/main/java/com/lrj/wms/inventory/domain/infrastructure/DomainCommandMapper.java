package com.lrj.wms.inventory.domain.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 移库、限制与独立调整单据；SQL 只在 XML。 */
public interface DomainCommandMapper {
    int insertMoveIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("sourceBalanceId") String sourceBalanceId, @Param("targetLocationId") String targetLocationId,
            @Param("targetBalanceId") String targetBalanceId, @Param("qty") BigDecimal qty, @Param("unit") String unit,
            @Param("reason") String reason, @Param("actorId") String actorId, @Param("operationId") String operationId,
            @Param("state") String state, @Param("now") Timestamp now);

    Map<String, Object> getMoveByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    int insertHoldIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("balanceId") String balanceId, @Param("locationId") String locationId, @Param("skuId") String skuId,
            @Param("lotId") String lotId, @Param("qty") BigDecimal qty, @Param("reason") String reason,
            @Param("evidenceRefs") String evidenceRefs, @Param("actorId") String actorId,
            @Param("operationId") String operationId, @Param("state") String state, @Param("now") Timestamp now);

    Map<String, Object> getHoldByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);

    Map<String, Object> lockHold(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("holdId") String holdId);

    int casReleaseHold(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("holdId") String holdId, @Param("expectedVersion") long expectedVersion,
            @Param("releasedBy") String releasedBy, @Param("releaseReason") String releaseReason,
            @Param("releaseOperationId") String releaseOperationId, @Param("now") Timestamp now);

    int insertAdjustmentIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("countLineId") String countLineId, @Param("balanceId") String balanceId,
            @Param("deltaQty") BigDecimal deltaQty, @Param("reason") String reason,
            @Param("evidenceRefs") String evidenceRefs, @Param("actorId") String actorId, @Param("state") String state,
            @Param("now") Timestamp now);

    Map<String, Object> getAdjustmentByKey(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId);

    Map<String, Object> lockAdjustment(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId);

    Map<String, Object> getAdjustment(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId);

    List<Map<String, Object>> listAdjustments(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("cursor") String cursor, @Param("limit") int limit);

    int casAdjustmentDecision(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId, @Param("expectedVersion") long expectedVersion,
            @Param("decision") String decision, @Param("decidedBy") String decidedBy,
            @Param("decisionReason") String decisionReason, @Param("state") String state, @Param("now") Timestamp now);

    int casAdjustmentApplied(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("adjustmentId") String adjustmentId, @Param("expectedVersion") long expectedVersion,
            @Param("applyOperationId") String applyOperationId, @Param("now") Timestamp now);
}
