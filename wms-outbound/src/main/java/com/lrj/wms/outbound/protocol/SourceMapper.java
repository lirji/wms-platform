package com.lrj.wms.outbound.protocol;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 出库来源协议表。必须带企业/仓条件。 */
public interface SourceMapper {
    @Insert("INSERT INTO source_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, fact_parent_id, "
            + "fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, attempt_no, state, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{sourceService}, #{action}, "
            + "#{factType}, #{factParentId}, #{factPartId}, #{factLineId}, #{id}, NULL, NULL, 0, #{state}, 0, #{now}, "
            + "#{now}) ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertEffect(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("action") String action, @Param("factType") String factType, @Param("factParentId") String factParentId,
            @Param("factPartId") String factPartId, @Param("factLineId") String factLineId, @Param("commandId") String commandId,
            @Param("state") String state, @Param("now") Timestamp now);

    @Select("SELECT id FROM source_effect WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND source_service=#{sourceService} AND action=#{action} AND fact_type=#{factType} "
            + "AND fact_parent_id=#{factParentId} AND fact_part_id=#{factPartId} AND fact_line_id=#{factLineId}")
    String findEffectId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("action") String action, @Param("factType") String factType,
            @Param("factParentId") String factParentId, @Param("factPartId") String factPartId,
            @Param("factLineId") String factLineId);

    @Select("SELECT id FROM source_effect WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND (active_command_id=#{commandId} OR applied_command_id=#{commandId})")
    String findEffectByCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);

    @Select("SELECT id, active_command_id, applied_command_id, attempt_no, state, version FROM source_effect "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId} FOR UPDATE")
    Map<String, Object> lockEffect(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId);

    @Update("UPDATE source_effect SET active_command_id=#{commandId}, attempt_no=GREATEST(attempt_no, #{attemptNo}), "
            + "state=#{state}, version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND id=#{effectId} AND applied_command_id IS NULL "
            + "AND (active_command_id IS NULL OR active_command_id=#{commandId})")
    int casBindActive(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("attemptNo") long attemptNo,
            @Param("state") String state, @Param("now") Timestamp now);

    @Update("UPDATE source_effect SET attempt_no=#{attemptNo}, active_command_id=#{commandId}, state=#{state}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{effectId} AND version=#{expectedVersion} AND applied_command_id IS NULL AND state=#{fromState}")
    int casNextAttempt(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("attemptNo") long attemptNo, @Param("commandId") String commandId,
            @Param("state") String state, @Param("fromState") String fromState, @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);

    @Update("UPDATE source_effect SET active_command_id=#{commandId}, state='SAFE_CLOSED', version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId} "
            + "AND applied_command_id IS NULL AND state NOT IN ('STARTED','UNKNOWN','APPLIED')")
    int casSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO source_command (id, enterprise_id, warehouse_id, command_id, source_operation_id, "
            + "source_execution_id, business_effect_key, action, execution_attempt_id, attempt_no, previous_command_id, "
            + "payload_digest, digest_version, payload_json, state, inventory_operation_id, posting_id, retry_at, "
            + "compensates_command_id, safe_close_id, safe_close_version, safe_close_evidence, version, created_at, "
            + "updated_at) VALUES (#{commandId}, #{enterpriseId}, #{warehouseId}, #{commandId}, #{operationId}, "
            + "#{executionId}, #{effectId}, #{action}, #{commandId}, #{attemptNo}, #{previousCommandId}, #{digest}, 1, "
            + "CAST(#{payload} AS JSON), #{state}, NULL, NULL, NULL, NULL, NULL, 0, NULL, 0, #{now}, #{now})")
    int insertCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("operationId") String operationId,
            @Param("executionId") String executionId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptNo") long attemptNo, @Param("previousCommandId") String previousCommandId,
            @Param("digest") String digest, @Param("payload") String payload, @Param("state") String state,
            @Param("now") Timestamp now);

    @Select("SELECT command_id, state, payload_digest, posting_id, attempt_no FROM source_command "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND business_effect_key=#{effectId} "
            + "ORDER BY attempt_no DESC LIMIT 1")
    Map<String, Object> findLatestCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("effectId") String effectId);

    @Update("UPDATE source_command SET safe_close_id=#{closeId}, safe_close_version=safe_close_version+1, "
            + "safe_close_evidence=CAST(#{evidence} AS JSON), updated_at=#{now} WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND command_id=#{commandId}")
    int markSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("closeId") String closeId, @Param("evidence") String evidence,
            @Param("now") Timestamp now);

    @Select("SELECT command_id, state, payload_digest, posting_id, business_effect_key, safe_close_id FROM source_command "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND command_id=#{commandId}")
    Map<String, Object> getCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);

    @Insert("INSERT INTO source_execution (id, enterprise_id, warehouse_id, command_id, action, physical_status, "
            + "stock_sync_status, physical_qty, posted_qty, actor_id, executed_at, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{commandId}, #{action}, 'EXECUTED', 'PENDING', #{qty}, 0, "
            + "#{actorId}, #{now}, 0, #{now}, #{now})")
    int insertExecution(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId, @Param("action") String action,
            @Param("qty") BigDecimal qty, @Param("actorId") String actorId, @Param("now") Timestamp now);

    @Insert("INSERT INTO source_outbox (event_id, enterprise_id, warehouse_id, command_id, event_type, payload, status, "
            + "next_attempt_at, version, created_at, updated_at) VALUES (#{eventId}, #{enterpriseId}, #{warehouseId}, "
            + "#{commandId}, #{eventType}, CAST(#{payload} AS JSON), 'PENDING', #{now}, 0, #{now}, #{now})")
    int insertOutbox(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId,
            @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO source_inbox (event_id, enterprise_id, warehouse_id, command_id, event_type, payload, "
            + "consumed_at, version, created_at, updated_at) VALUES (#{eventId}, #{enterpriseId}, #{warehouseId}, "
            + "#{commandId}, #{eventType}, CAST(#{payload} AS JSON), #{now}, 0, #{now}, #{now})")
    int insertInbox(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId,
            @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Timestamp now);

    @Update("UPDATE source_command SET state=#{state}, inventory_operation_id=#{operationId}, posting_id=#{postingId}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND command_id=#{commandId}")
    int updateCommandResult(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("state") String state, @Param("operationId") String operationId,
            @Param("postingId") String postingId, @Param("now") Timestamp now);

    @Update("UPDATE source_execution SET stock_sync_status=#{syncStatus}, posted_qty=#{postedQty}, version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND command_id=#{commandId}")
    int updateExecutionSync(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("syncStatus") String syncStatus, @Param("postedQty") BigDecimal postedQty,
            @Param("now") Timestamp now);

    @Update("UPDATE source_effect SET applied_command_id=#{commandId}, state=#{state}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId} "
            + "AND (applied_command_id IS NULL AND active_command_id=#{commandId} OR applied_command_id=#{commandId})")
    int updateEffectApplied(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("state") String state,
            @Param("now") Timestamp now);
}
