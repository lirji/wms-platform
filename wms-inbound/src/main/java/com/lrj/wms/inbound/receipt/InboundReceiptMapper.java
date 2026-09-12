package com.lrj.wms.inbound.receipt;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
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

    @Select("SELECT id, result_code, accepted_qty, rejected_qty, source_version FROM quality_inspection "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND inbound_line_id=#{lineId} "
            + "ORDER BY source_version DESC LIMIT 1")
    Map<String, Object> latestInspection(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("lineId") String lineId);

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

    @Insert("INSERT IGNORE INTO inbound_receipt_part (id, enterprise_id, warehouse_id, receipt_session_id, inbound_order_id, "
            + "inbound_line_id, part_id, qty, command_id, actor_id, state, version, created_at, updated_at) VALUES (#{id}, "
            + "#{enterpriseId}, #{warehouseId}, #{sessionId}, #{orderId}, #{lineId}, #{partId}, #{qty}, #{commandId}, "
            + "#{actorId}, 'REGISTERED', 0, #{now}, #{now})")
    int insertPartIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sessionId") String sessionId, @Param("orderId") String orderId,
            @Param("lineId") String lineId, @Param("partId") String partId, @Param("qty") BigDecimal qty,
            @Param("commandId") String commandId, @Param("actorId") String actorId, @Param("now") Timestamp now);

    @Select("SELECT id, part_id, qty, command_id, state FROM inbound_receipt_part WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND receipt_session_id=#{sessionId} AND part_id=#{partId} "
            + "AND inbound_line_id=#{lineId} FOR UPDATE")
    Map<String, Object> lockPart(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sessionId") String sessionId, @Param("partId") String partId, @Param("lineId") String lineId);

    @Insert("INSERT IGNORE INTO device_observation_binding (id, enterprise_id, warehouse_id, device_id, session_id, "
            + "sequence_no, business_effect_key, receipt_session_id, part_id, inbound_line_id, command_id, payload_digest, "
            + "digest_version, state, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, "
            + "#{deviceId}, #{deviceSessionId}, #{sequenceNo}, #{effectKey}, #{receiptSessionId}, #{partId}, #{lineId}, "
            + "#{commandId}, #{digest}, 1, #{state}, 0, #{now}, #{now})")
    int insertObservationIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("deviceId") String deviceId,
            @Param("deviceSessionId") String deviceSessionId, @Param("sequenceNo") long sequenceNo,
            @Param("effectKey") String effectKey, @Param("receiptSessionId") String receiptSessionId,
            @Param("partId") String partId, @Param("lineId") String lineId, @Param("commandId") String commandId,
            @Param("digest") String digest, @Param("state") String state, @Param("now") Timestamp now);

    @Select("SELECT id, business_effect_key, receipt_session_id, part_id, inbound_line_id, command_id, payload_digest, "
            + "digest_version, state FROM device_observation_binding WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND device_id=#{deviceId} AND session_id=#{deviceSessionId} "
            + "AND sequence_no=#{sequenceNo} FOR UPDATE")
    Map<String, Object> lockObservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("deviceId") String deviceId, @Param("deviceSessionId") String deviceSessionId,
            @Param("sequenceNo") long sequenceNo);

    @Select("SELECT id, business_effect_key, receipt_session_id, part_id, inbound_line_id, command_id, payload_digest, "
            + "digest_version, state FROM device_observation_binding WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND device_id=#{deviceId} AND session_id=#{deviceSessionId} "
            + "AND sequence_no=#{sequenceNo}")
    Map<String, Object> getObservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("deviceId") String deviceId, @Param("deviceSessionId") String deviceSessionId,
            @Param("sequenceNo") long sequenceNo);

    @Update("UPDATE device_observation_binding SET business_effect_key=#{effectKey}, command_id=#{commandId}, state='BOUND', "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{id}")
    int bindObservation(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("effectKey") String effectKey, @Param("commandId") String commandId,
            @Param("now") Timestamp now);

    @Select("SELECT id, external_source, external_no, owner_id, status, version, created_at, updated_at "
            + "FROM inbound_order WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> listOrders(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("limit") int limit);

    @Select("SELECT id, external_source, external_no, owner_id, status, version, created_at, updated_at "
            + "FROM inbound_order WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{orderId}")
    Map<String, Object> getOrder(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("orderId") String orderId);

    @Select("SELECT id, external_line_id, sku_id, expected_qty, received_physical_qty, received_posted_qty, "
            + "putaway_physical_qty, putaway_posted_qty, closed_qty, base_unit, stock_sync_status, version "
            + "FROM inbound_line WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND order_id=#{orderId} ORDER BY id")
    List<Map<String, Object>> listLines(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId);

    @Select("SELECT id, task_type, document_id, document_line_id, planned_qty, completed_qty, state "
            + "FROM inbound_task WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND document_id=#{orderId} ORDER BY created_at")
    List<Map<String, Object>> listTasks(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("orderId") String orderId);
}
