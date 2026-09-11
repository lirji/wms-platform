package com.lrj.wms.outbound.order;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 出库单/行/任务/包裹。必须带企业/仓条件。 */
public interface OutboundOrderMapper {
    @Insert("INSERT IGNORE INTO outbound_order (id, enterprise_id, warehouse_id, allocation_id, attempt_id, owner_id, "
            + "execution_authorization_id, status, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, "
            + "#{warehouseId}, #{allocationId}, #{attemptId}, #{ownerId}, #{authId}, #{status}, 0, #{now}, #{now})")
    int insertOrderIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId, @Param("ownerId") String ownerId, @Param("authId") String authId,
            @Param("status") String status, @Param("now") Timestamp now);

    @Select("SELECT id, allocation_id, attempt_id, owner_id, execution_authorization_id, status, version "
            + "FROM outbound_order WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND allocation_id=#{allocationId} AND attempt_id=#{attemptId} FOR UPDATE")
    Map<String, Object> lockOrderByAttempt(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("allocationId") String allocationId,
            @Param("attemptId") String attemptId);

    @Select("SELECT id, allocation_id, attempt_id, owner_id, execution_authorization_id, status, version "
            + "FROM outbound_order WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{id} "
            + "FOR UPDATE")
    Map<String, Object> lockOrder(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    @Insert("INSERT IGNORE INTO outbound_line (id, enterprise_id, warehouse_id, order_id, order_line_id, sku_id, "
            + "allocated_qty, picked_physical_qty, picked_posted_qty, packed_physical_qty, packed_posted_qty, "
            + "shipped_physical_qty, shipped_posted_qty, cancelled_qty, base_unit, stock_sync_status, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{orderId}, #{orderLineId}, "
            + "#{skuId}, #{qty}, 0, 0, 0, 0, 0, 0, 0, #{unit}, 'PENDING', 0, #{now}, #{now})")
    int insertLineIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("orderLineId") String orderLineId, @Param("skuId") String skuId, @Param("qty") BigDecimal qty,
            @Param("unit") String unit, @Param("now") Timestamp now);

    @Select("SELECT id, order_id, order_line_id, sku_id, allocated_qty, picked_physical_qty, picked_posted_qty, "
            + "packed_physical_qty, packed_posted_qty, shipped_physical_qty, shipped_posted_qty, cancelled_qty, "
            + "stock_sync_status, version FROM outbound_line WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND id=#{lineId} FOR UPDATE")
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    @Select("SELECT id, order_id, order_line_id, sku_id, allocated_qty, picked_physical_qty, picked_posted_qty, "
            + "packed_physical_qty, packed_posted_qty, shipped_physical_qty, shipped_posted_qty, cancelled_qty, "
            + "stock_sync_status, version FROM outbound_line WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND order_id=#{orderId} AND order_line_id=#{orderLineId} FOR UPDATE")
    Map<String, Object> lockLineByOrderLine(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("orderLineId") String orderLineId);

    @Update("UPDATE outbound_order SET status=#{status}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{orderId}")
    int updateOrderStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId, @Param("status") String status, @Param("now") Timestamp now);

    @Update("UPDATE outbound_line SET picked_physical_qty=picked_physical_qty+#{qty}, stock_sync_status='PENDING', "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId}")
    int addPickedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE outbound_line SET picked_posted_qty=picked_posted_qty+#{qty}, stock_sync_status=#{syncStatus}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId} AND picked_posted_qty+#{qty}<=picked_physical_qty")
    int addPickedPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    @Update("UPDATE outbound_line SET packed_physical_qty=packed_physical_qty+#{qty}, stock_sync_status='PENDING', "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId}")
    int addPackedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE outbound_line SET cancelled_qty=cancelled_qty+#{qty}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{lineId}")
    int addCancelled(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Insert("INSERT INTO outbound_task (id, enterprise_id, warehouse_id, task_type, document_id, document_line_id, "
            + "source_location_id, target_location_id, planned_qty, completed_qty, state, assignee_id, claim_epoch, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{taskType}, "
            + "#{documentId}, #{lineId}, #{sourceLocationId}, #{targetLocationId}, #{plannedQty}, 0, #{state}, NULL, "
            + "0, 0, #{now}, #{now})")
    int insertTask(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskType") String taskType,
            @Param("documentId") String documentId, @Param("lineId") String lineId,
            @Param("sourceLocationId") String sourceLocationId, @Param("targetLocationId") String targetLocationId,
            @Param("plannedQty") BigDecimal plannedQty, @Param("state") String state, @Param("now") Timestamp now);

    @Select("SELECT id, document_id, document_line_id, planned_qty, completed_qty, state, claim_epoch, version "
            + "FROM outbound_task WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{id} "
            + "FOR UPDATE")
    Map<String, Object> lockTask(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    @Update("UPDATE outbound_task SET completed_qty=completed_qty+#{qty}, state=#{state}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{id}")
    int addTaskCompleted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("qty") BigDecimal qty, @Param("state") String state,
            @Param("now") Timestamp now);

    @Insert("INSERT INTO outbound_package (id, enterprise_id, warehouse_id, order_id, package_no, status, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{orderId}, #{packageNo}, "
            + "#{status}, 0, #{now}, #{now})")
    int insertPackage(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("packageNo") String packageNo, @Param("status") String status, @Param("now") Timestamp now);

    @Insert("INSERT INTO package_line (id, enterprise_id, warehouse_id, package_id, outbound_line_id, qty, version, "
            + "created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{packageId}, #{lineId}, #{qty}, "
            + "0, #{now}, #{now})")
    int insertPackageLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("packageId") String packageId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);
}