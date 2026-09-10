package com.lrj.wms.inventory.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 库存命令、凭证与授权。调用方必须带企业/仓条件。 */
public interface StockCommandMapper {
    @Insert("INSERT IGNORE INTO stock_command (id, enterprise_id, warehouse_id, source_service, command_id, action, "
            + "business_effect_key, execution_attempt_id, attempt_no, previous_command_id, payload_digest, digest_version, "
            + "state, settlement_digest, result_json, compensates_command_id, safe_close_id, safe_close_version, "
            + "safe_close_evidence, version, created_at, updated_at) VALUES (#{commandId}, #{enterpriseId}, #{warehouseId}, "
            + "#{sourceService}, #{commandId}, #{action}, #{effectId}, #{attemptId}, #{attemptNo}, #{previousCommandId}, "
            + "#{digest}, #{digestVersion}, #{state}, NULL, NULL, NULL, NULL, 0, NULL, 0, #{now}, #{now})")
    int insertIgnore(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId, @Param("action") String action,
            @Param("effectId") String effectId, @Param("attemptId") String attemptId, @Param("attemptNo") long attemptNo,
            @Param("previousCommandId") String previousCommandId, @Param("digest") String digest,
            @Param("digestVersion") int digestVersion, @Param("state") String state, @Param("now") Timestamp now);

    @Select("SELECT command_id, action, business_effect_key, execution_attempt_id, attempt_no, payload_digest, "
            + "digest_version, state FROM stock_command WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND source_service=#{sourceService} AND command_id=#{commandId} FOR UPDATE")
    Map<String, Object> lockByCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId);

    @Select("SELECT command_id, action, business_effect_key, execution_attempt_id, attempt_no, payload_digest, "
            + "digest_version, state FROM stock_command WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND source_service=#{sourceService} AND command_id=#{commandId}")
    Map<String, Object> getByCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId);

    @Update("UPDATE stock_command SET state=#{toState}, result_json=CAST(#{resultJson} AS JSON), version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND source_service=#{sourceService} AND command_id=#{commandId} AND state=#{fromState}")
    int casState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId,
            @Param("fromState") String fromState, @Param("toState") String toState, @Param("resultJson") String resultJson,
            @Param("now") Timestamp now);

    @Insert("INSERT INTO stock_posting (id, enterprise_id, warehouse_id, source_service, command_id, business_effect_key, "
            + "action, execution_attempt_id, posting_type, quantity, source_execution_id, source_document_id, "
            + "ledger_manifest, result_version, original_posting_id, reversed_qty, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{sourceService}, #{commandId}, #{effectId}, #{action}, "
            + "#{attemptId}, #{postingType}, #{quantity}, #{sourceExecutionId}, #{sourceDocumentId}, "
            + "CAST(#{manifest} AS JSON), 1, NULL, 0, 0, #{now}, #{now})")
    int insertPosting(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptId") String attemptId, @Param("postingType") String postingType, @Param("quantity") BigDecimal quantity,
            @Param("sourceExecutionId") String sourceExecutionId, @Param("sourceDocumentId") String sourceDocumentId,
            @Param("manifest") String manifest, @Param("now") Timestamp now);

    @Insert("INSERT IGNORE INTO execution_permit (id, enterprise_id, warehouse_id, source_service, permit_id, command_id, "
            + "business_effect_key, execution_attempt_id, attempt_no, source_task_id, source_task_epoch, gate_epoch, action, "
            + "payload_digest, quantity, actual_qty, not_executed_qty, settlement_digest, state, started_at, posted_at, "
            + "version, created_at, updated_at) VALUES (#{permitId}, #{enterpriseId}, #{warehouseId}, #{sourceService}, "
            + "#{permitId}, #{commandId}, #{effectId}, #{attemptId}, #{attemptNo}, #{taskId}, #{taskEpoch}, 0, #{action}, "
            + "#{digest}, #{quantity}, #{actualQty}, #{notExecutedQty}, NULL, #{state}, #{startedAt}, #{postedAt}, 0, #{now}, "
            + "#{now})")
    int insertPermit(@Param("permitId") String permitId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("attemptId") String attemptId,
            @Param("attemptNo") long attemptNo, @Param("taskId") String taskId, @Param("taskEpoch") long taskEpoch,
            @Param("action") String action, @Param("digest") String digest, @Param("quantity") BigDecimal quantity,
            @Param("actualQty") BigDecimal actualQty, @Param("notExecutedQty") BigDecimal notExecutedQty,
            @Param("state") String state, @Param("startedAt") Timestamp startedAt, @Param("postedAt") Timestamp postedAt,
            @Param("now") Timestamp now);

    @Select("SELECT permit_id, state FROM execution_permit WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND source_service=#{sourceService} AND command_id=#{commandId} FOR UPDATE")
    Map<String, Object> lockPermitByCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId);
}
