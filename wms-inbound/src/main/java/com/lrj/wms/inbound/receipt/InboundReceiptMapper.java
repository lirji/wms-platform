package com.lrj.wms.inbound.receipt;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 入库单/行/质检/任务。必须带企业/仓条件。 */
public interface InboundReceiptMapper {
    @Insert("INSERT INTO inbound_order (id, enterprise_id, warehouse_id, external_source, external_no, owner_id, status, "
            + "expected_at, source_version, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, "
            + "#{externalSource}, #{externalNo}, #{ownerId}, #{status}, NULL, 0, 0, #{now}, #{now})")
    int insertOrder(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("externalSource") String externalSource,
            @Param("externalNo") String externalNo, @Param("ownerId") String ownerId, @Param("status") String status,
            @Param("now") Timestamp now);

    @Insert("INSERT INTO inbound_line (id, enterprise_id, warehouse_id, order_id, external_line_id, sku_id, expected_qty, "
            + "received_physical_qty, received_posted_qty, putaway_physical_qty, putaway_posted_qty, closed_qty, base_unit, "
            + "stock_sync_status, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{orderId}, "
            + "#{externalLineId}, #{skuId}, #{expectedQty}, 0, 0, 0, 0, 0, #{unit}, 'PENDING', 0, #{now}, #{now})")
    int insertLine(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId,
            @Param("externalLineId") String externalLineId, @Param("skuId") String skuId,
            @Param("expectedQty") BigDecimal expectedQty, @Param("unit") String unit, @Param("now") Timestamp now);

    @Select("SELECT id, order_id, sku_id, expected_qty, received_physical_qty, received_posted_qty, putaway_physical_qty, "
            + "putaway_posted_qty, closed_qty, stock_sync_status, version FROM inbound_line "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{lineId} FOR UPDATE")
    Map<String, Object> lockLine(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId);

    @Update("UPDATE inbound_line SET received_physical_qty=received_physical_qty+#{qty}, stock_sync_status='PENDING', "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId}")
    int addReceivedPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE inbound_line SET received_posted_qty=received_posted_qty+#{qty}, stock_sync_status=#{syncStatus}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId} AND received_posted_qty+#{qty}<=received_physical_qty")
    int addReceivedPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    @Update("UPDATE inbound_line SET putaway_physical_qty=putaway_physical_qty+#{qty}, stock_sync_status='PENDING', "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId}")
    int addPutawayPhysical(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);

    @Update("UPDATE inbound_line SET putaway_posted_qty=putaway_posted_qty+#{qty}, stock_sync_status=#{syncStatus}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{lineId} AND putaway_posted_qty+#{qty}<=putaway_physical_qty")
    int addPutawayPosted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("lineId") String lineId, @Param("qty") BigDecimal qty, @Param("syncStatus") String syncStatus,
            @Param("now") Timestamp now);

    @Update("UPDATE inbound_order SET status=#{status}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{orderId}")
    int updateOrderStatus(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId, @Param("status") String status, @Param("now") Timestamp now);

    @Insert("INSERT INTO quality_inspection (id, enterprise_id, warehouse_id, inbound_line_id, inspected_qty, accepted_qty, "
            + "rejected_qty, result_code, source_version, actor_id, evidence_refs, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{lineId}, #{inspected}, #{accepted}, #{rejected}, "
            + "#{result}, #{sourceVersion}, #{actorId}, NULL, 0, #{now}, #{now})")
    int insertInspection(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lineId") String lineId,
            @Param("inspected") BigDecimal inspected, @Param("accepted") BigDecimal accepted,
            @Param("rejected") BigDecimal rejected, @Param("result") String result,
            @Param("sourceVersion") long sourceVersion, @Param("actorId") String actorId, @Param("now") Timestamp now);

    @Insert("INSERT INTO inbound_task (id, enterprise_id, warehouse_id, task_type, document_id, document_line_id, "
            + "source_location_id, target_location_id, planned_qty, completed_qty, state, assignee_id, claim_epoch, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{taskType}, #{orderId}, "
            + "#{lineId}, #{sourceLocationId}, #{targetLocationId}, #{planned}, 0, #{state}, NULL, 0, 0, #{now}, #{now})")
    int insertTask(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("taskType") String taskType, @Param("orderId") String orderId,
            @Param("lineId") String lineId, @Param("sourceLocationId") String sourceLocationId,
            @Param("targetLocationId") String targetLocationId, @Param("planned") BigDecimal planned,
            @Param("state") String state, @Param("now") Timestamp now);

    @Update("UPDATE inbound_task SET completed_qty=completed_qty+#{qty}, state=#{state}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{taskId}")
    int addTaskCompleted(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("taskId") String taskId, @Param("qty") BigDecimal qty, @Param("state") String state,
            @Param("now") Timestamp now);
}
