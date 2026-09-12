package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 调拨总单、仓级子单、在途行与操作事实。 */
public interface TransferMapper {
    @Insert("INSERT IGNORE INTO transfer_order (id, enterprise_id, source_warehouse_id, target_warehouse_id, status, "
            + "source_document_id, target_document_id, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{sourceWarehouseId}, #{targetWarehouseId}, #{status}, NULL, NULL, 0, #{now}, #{now})")
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("sourceWarehouseId") String sourceWarehouseId, @Param("targetWarehouseId") String targetWarehouseId,
            @Param("status") String status, @Param("now") Timestamp now);

    @Select("SELECT id, source_warehouse_id, target_warehouse_id, status, source_document_id, target_document_id, version "
            + "FROM transfer_order WHERE enterprise_id=#{enterpriseId} AND id=#{id} FOR UPDATE")
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    @Insert("INSERT INTO transfer_leg (id, enterprise_id, transfer_id, warehouse_id, role, status, document_id, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{transferId}, #{warehouseId}, #{role}, #{status}, "
            + "NULL, 0, #{now}, #{now})")
    int insertLeg(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("warehouseId") String warehouseId, @Param("role") String role,
            @Param("status") String status, @Param("now") Timestamp now);

    @Insert("INSERT INTO transfer_line (id, enterprise_id, transfer_id, sku_id, business_lot_key, source_lot_id, "
            + "target_lot_id, planned_qty, issued_qty, received_qty, loss_confirmed_qty, active_receipt_quota, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{transferId}, #{skuId}, #{businessLotKey}, "
            + "#{sourceLotId}, NULL, #{plannedQty}, 0, 0, 0, 0, 0, #{now}, #{now})")
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("skuId") String skuId,
            @Param("businessLotKey") String businessLotKey, @Param("sourceLotId") String sourceLotId,
            @Param("plannedQty") BigDecimal plannedQty, @Param("now") Timestamp now);

    @Select("SELECT id, sku_id, business_lot_key, source_lot_id, target_lot_id, planned_qty, issued_qty, received_qty, "
            + "loss_confirmed_qty, active_receipt_quota, version FROM transfer_line WHERE enterprise_id=#{enterpriseId} "
            + "AND transfer_id=#{transferId} AND id=#{lineId} FOR UPDATE")
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId);

    @Select("SELECT id, sku_id, planned_qty, issued_qty, received_qty, loss_confirmed_qty, active_receipt_quota "
            + "FROM transfer_line WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId}")
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId);

    @Select("SELECT id, warehouse_id, role, status FROM transfer_leg WHERE enterprise_id=#{enterpriseId} "
            + "AND transfer_id=#{transferId}")
    List<Map<String, Object>> listLegs(@Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId);

    @Insert("INSERT IGNORE INTO transfer_fact (id, enterprise_id, transfer_id, line_id, warehouse_id, action, "
            + "operation_id, quantity, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{transferId}, "
            + "#{lineId}, #{warehouseId}, #{action}, #{operationId}, #{qty}, 0, #{now}, #{now})")
    int insertFactIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("lineId") String lineId,
            @Param("warehouseId") String warehouseId, @Param("action") String action,
            @Param("operationId") String operationId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Select("SELECT transfer_id, line_id, warehouse_id, action, operation_id, quantity FROM transfer_fact "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND action=#{action} "
            + "AND operation_id=#{operationId}")
    Map<String, Object> getFact(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("action") String action, @Param("operationId") String operationId);

    @Update("UPDATE transfer_line SET issued_qty=issued_qty+#{qty}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND id=#{lineId} "
            + "AND issued_qty+#{qty}<=planned_qty")
    int addIssued(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE transfer_line SET received_qty=received_qty+#{qty}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND id=#{lineId} "
            + "AND received_qty+loss_confirmed_qty+active_receipt_quota+#{qty}<=issued_qty")
    int addReceived(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE transfer_order SET status=#{status}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND id=#{transferId}")
    int updateOrderStatus(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("status") String status, @Param("now") Timestamp now);

    @Update("UPDATE transfer_leg SET status=#{status}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND warehouse_id=#{warehouseId}")
    int updateLegStatus(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("warehouseId") String warehouseId, @Param("status") String status, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO receipt_authorization (id, enterprise_id, transfer_id, transfer_line_id, "
            + "target_warehouse_id, target_client_operation_id, quantity, state, token_version, target_result_ref, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{transferId}, #{lineId}, "
            + "#{warehouseId}, #{clientOperationId}, #{qty}, 'OPEN', 1, NULL, 0, #{now}, #{now})")
    int insertAuthIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("transferId") String transferId, @Param("lineId") String lineId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Select("SELECT id, transfer_id, transfer_line_id, target_warehouse_id, target_client_operation_id, quantity, "
            + "state, token_version, target_result_ref FROM receipt_authorization WHERE enterprise_id=#{enterpriseId} "
            + "AND transfer_line_id=#{lineId} AND target_client_operation_id=#{clientOperationId} FOR UPDATE")
    Map<String, Object> lockAuthByClient(@Param("enterpriseId") String enterpriseId, @Param("lineId") String lineId,
            @Param("clientOperationId") String clientOperationId);

    @Select("SELECT id, transfer_id, transfer_line_id, target_warehouse_id, target_client_operation_id, quantity, "
            + "state, token_version, target_result_ref FROM receipt_authorization WHERE enterprise_id=#{enterpriseId} "
            + "AND id=#{id} FOR UPDATE")
    Map<String, Object> lockAuth(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    @Update("UPDATE transfer_line SET active_receipt_quota=active_receipt_quota+#{qty}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND id=#{lineId} "
            + "AND received_qty+loss_confirmed_qty+active_receipt_quota+#{qty}<=issued_qty")
    int addQuota(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE transfer_line SET received_qty=received_qty+#{qty}, active_receipt_quota=active_receipt_quota-#{qty}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} "
            + "AND id=#{lineId} AND active_receipt_quota>=#{qty}")
    int consumeQuota(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE transfer_line SET active_receipt_quota=active_receipt_quota-#{qty}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND id=#{lineId} "
            + "AND active_receipt_quota>=#{qty}")
    int releaseQuota(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE transfer_line SET loss_confirmed_qty=loss_confirmed_qty+#{qty}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND id=#{lineId} "
            + "AND received_qty+loss_confirmed_qty+active_receipt_quota+#{qty}<=issued_qty")
    int addLoss(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE receipt_authorization SET state=#{toState}, target_result_ref=#{resultRef}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND id=#{id} AND state=#{fromState} "
            + "AND token_version=#{tokenVersion}")
    int casAuthState(@Param("enterpriseId") String enterpriseId, @Param("id") String id,
            @Param("fromState") String fromState, @Param("toState") String toState,
            @Param("tokenVersion") long tokenVersion, @Param("resultRef") String resultRef, @Param("now") Timestamp now);

    @Update("UPDATE transfer_line SET target_lot_id=#{targetLotId}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND transfer_id=#{transferId} AND id=#{lineId} "
            + "AND (target_lot_id IS NULL OR target_lot_id=#{targetLotId})")
    int bindTargetLot(@Param("enterpriseId") String enterpriseId, @Param("transferId") String transferId,
            @Param("lineId") String lineId, @Param("targetLotId") String targetLotId, @Param("now") Timestamp now);

    @Select("SELECT id, source_warehouse_id, target_warehouse_id, status, version, created_at "
            + "FROM transfer_order WHERE enterprise_id=#{enterpriseId} ORDER BY created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> listOrders(@Param("enterpriseId") String enterpriseId, @Param("limit") int limit);
}
