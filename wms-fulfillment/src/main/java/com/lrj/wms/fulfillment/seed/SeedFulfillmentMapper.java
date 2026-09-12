package com.lrj.wms.fulfillment.seed;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 履约/调拨演示单幂等写入。 */
public interface SeedFulfillmentMapper {
    /** insertOrderIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceOrderNo") String sourceOrderNo,
            @Param("digest") String digest, @Param("status") String status, @Param("strategyVersion") long strategyVersion,
            @Param("now") Timestamp now);

    /** insertLineIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("sourceLineId") String sourceLineId,
            @Param("skuId") String skuId, @Param("qty") BigDecimal qty, @Param("unit") String unit,
            @Param("now") Timestamp now);

    /** insertAttemptIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertAttemptIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("state") String state, @Param("deadline") Timestamp deadline,
            @Param("hash") String hash, @Param("digest") String digest, @Param("now") Timestamp now);

    /** insertParticipantIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertParticipantIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("warehouseId") String warehouseId, @Param("state") String state,
            @Param("now") Timestamp now);

    /** insertParticipantLineIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertParticipantLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("participantId") String participantId, @Param("orderLineId") String orderLineId,
            @Param("skuId") String skuId, @Param("qty") BigDecimal qty, @Param("unit") String unit,
            @Param("now") Timestamp now);

    /** lockOrder：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /** casActiveAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casActiveAttempt(@Param("enterpriseId") String enterpriseId, @Param("orderId") String orderId,
            @Param("attemptId") String attemptId, @Param("expectedActive") String expectedActive,
            @Param("version") long version, @Param("now") Timestamp now);

    /** insertTransferIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertTransferIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceWarehouseId") String sourceWarehouseId, @Param("targetWarehouseId") String targetWarehouseId,
            @Param("status") String status, @Param("now") Timestamp now);

    /** insertTransferLegIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertTransferLegIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("warehouseId") String warehouseId, @Param("role") String role,
            @Param("status") String status, @Param("now") Timestamp now);

    /** insertTransferLineIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertTransferLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("skuId") String skuId,
            @Param("businessLotKey") String businessLotKey, @Param("sourceLotId") String sourceLotId,
            @Param("plannedQty") BigDecimal plannedQty, @Param("now") Timestamp now);

    /** countOrders：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countOrders(@Param("enterpriseId") String enterpriseId);

    /** countAttempts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countAttempts(@Param("enterpriseId") String enterpriseId);

    /** countTransfers：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int countTransfers(@Param("enterpriseId") String enterpriseId);
}
