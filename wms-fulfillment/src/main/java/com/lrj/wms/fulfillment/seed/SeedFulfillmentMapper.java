package com.lrj.wms.fulfillment.seed;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 履约/调拨演示单幂等写入。 */
public interface SeedFulfillmentMapper {
    @Insert("INSERT IGNORE INTO fulfillment_order (id, enterprise_id, source_system, source_order_no, request_digest, "
            + "status, strategy_version, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{sourceSystem}, "
            + "#{sourceOrderNo}, #{digest}, #{status}, #{strategyVersion}, 0, #{now}, #{now})")
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceSystem") String sourceSystem, @Param("sourceOrderNo") String sourceOrderNo,
            @Param("digest") String digest, @Param("status") String status, @Param("strategyVersion") long strategyVersion,
            @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO fulfillment_line (id, enterprise_id, fulfillment_id, source_line_id, sku_id, requested_qty, "
            + "base_unit, min_remaining_days, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{fulfillmentId}, #{sourceLineId}, #{skuId}, #{qty}, #{unit}, 0, 0, #{now}, #{now})")
    int insertLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("sourceLineId") String sourceLineId,
            @Param("skuId") String skuId, @Param("qty") BigDecimal qty, @Param("unit") String unit,
            @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO allocation_attempt (id, enterprise_id, fulfillment_id, state, deadline, "
            + "participant_set_hash, allocation_digest, cancel_requested, launch_epoch, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{fulfillmentId}, #{state}, #{deadline}, #{hash}, #{digest}, 0, 0, 0, "
            + "#{now}, #{now})")
    int insertAttemptIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("fulfillmentId") String fulfillmentId, @Param("state") String state, @Param("deadline") Timestamp deadline,
            @Param("hash") String hash, @Param("digest") String digest, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO allocation_participant (id, enterprise_id, attempt_id, warehouse_id, state, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{attemptId}, #{warehouseId}, #{state}, 0, #{now}, "
            + "#{now})")
    int insertParticipantIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("attemptId") String attemptId, @Param("warehouseId") String warehouseId, @Param("state") String state,
            @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO participant_line (id, enterprise_id, participant_id, order_line_id, sku_id, qty, base_unit, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{participantId}, #{orderLineId}, #{skuId}, "
            + "#{qty}, #{unit}, 0, #{now}, #{now})")
    int insertParticipantLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("participantId") String participantId, @Param("orderLineId") String orderLineId,
            @Param("skuId") String skuId, @Param("qty") BigDecimal qty, @Param("unit") String unit,
            @Param("now") Timestamp now);

    @Select("SELECT id, source_system, source_order_no, request_digest, status, strategy_version, active_attempt_id, "
            + "version FROM fulfillment_order WHERE enterprise_id=#{enterpriseId} AND id=#{id} FOR UPDATE")
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    @Update("UPDATE fulfillment_order SET active_attempt_id=#{attemptId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND id=#{orderId} AND version=#{version} "
            + "AND (active_attempt_id IS NULL OR active_attempt_id=#{expectedActive})")
    int casActiveAttempt(@Param("enterpriseId") String enterpriseId, @Param("orderId") String orderId,
            @Param("attemptId") String attemptId, @Param("expectedActive") String expectedActive,
            @Param("version") long version, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO transfer_order (id, enterprise_id, source_warehouse_id, target_warehouse_id, status, "
            + "source_document_id, target_document_id, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{sourceWarehouseId}, #{targetWarehouseId}, #{status}, NULL, NULL, 0, #{now}, #{now})")
    int insertTransferIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceWarehouseId") String sourceWarehouseId, @Param("targetWarehouseId") String targetWarehouseId,
            @Param("status") String status, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO transfer_leg (id, enterprise_id, transfer_id, warehouse_id, role, status, document_id, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{transferId}, #{warehouseId}, #{role}, "
            + "#{status}, NULL, 0, #{now}, #{now})")
    int insertTransferLegIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("warehouseId") String warehouseId, @Param("role") String role,
            @Param("status") String status, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO transfer_line (id, enterprise_id, transfer_id, sku_id, business_lot_key, source_lot_id, "
            + "target_lot_id, planned_qty, issued_qty, received_qty, loss_confirmed_qty, active_receipt_quota, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{transferId}, #{skuId}, #{businessLotKey}, "
            + "#{sourceLotId}, NULL, #{plannedQty}, 0, 0, 0, 0, 0, #{now}, #{now})")
    int insertTransferLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("skuId") String skuId,
            @Param("businessLotKey") String businessLotKey, @Param("sourceLotId") String sourceLotId,
            @Param("plannedQty") BigDecimal plannedQty, @Param("now") Timestamp now);

    @Select("SELECT COUNT(*) FROM fulfillment_order WHERE enterprise_id=#{enterpriseId}")
    int countOrders(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM allocation_attempt WHERE enterprise_id=#{enterpriseId}")
    int countAttempts(@Param("enterpriseId") String enterpriseId);

    @Select("SELECT COUNT(*) FROM transfer_order WHERE enterprise_id=#{enterpriseId}")
    int countTransfers(@Param("enterpriseId") String enterpriseId);
}
