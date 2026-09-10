package com.lrj.wms.inventory.effect.infrastructure;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 效果身份与尝试的仓内读写；调用方必须带企业/仓条件。 */
public interface EffectMapper {
    /** 按权威事实登记效果；冲突时保持原行。 */
    @Insert("INSERT INTO stock_effect (id, enterprise_id, warehouse_id, source_service, action, fact_type, fact_parent_id, "
            + "fact_part_id, fact_line_id, business_effect_key, active_command_id, applied_command_id, attempt_no, state, "
            + "version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{sourceService}, #{action}, "
            + "#{factType}, #{factParentId}, #{factPartId}, #{factLineId}, #{id}, NULL, NULL, 0, #{state}, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertEffect(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("action") String action, @Param("factType") String factType, @Param("factParentId") String factParentId,
            @Param("factPartId") String factPartId, @Param("factLineId") String factLineId, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 按权威事实读取已有不透明身份。 */
    @Select("SELECT id FROM stock_effect WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND source_service=#{sourceService} AND action=#{action} AND fact_type=#{factType} "
            + "AND fact_parent_id=#{factParentId} AND fact_part_id=#{factPartId} AND fact_line_id=#{factLineId}")
    String findEffectId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("action") String action, @Param("factType") String factType,
            @Param("factParentId") String factParentId, @Param("factPartId") String factPartId,
            @Param("factLineId") String factLineId);

    /** 按不透明身份读取效果行。 */
    @Select("SELECT id, action, fact_type, fact_parent_id, fact_part_id, fact_line_id, business_effect_key, "
            + "active_command_id, applied_command_id, attempt_no, state, version FROM stock_effect "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId} FOR UPDATE")
    Map<String, Object> lockEffect(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId);

    /** 无锁读取，供查询接口。 */
    @Select("SELECT id, action, fact_type, fact_parent_id, fact_part_id, fact_line_id, business_effect_key, "
            + "active_command_id, applied_command_id, attempt_no, state, version FROM stock_effect "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId}")
    Map<String, Object> getEffect(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId);

    /** CAS 发放下一尝试号并切换活动命令。 */
    @Update("UPDATE stock_effect SET attempt_no=#{attemptNo}, active_command_id=#{commandId}, state=#{state}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{effectId} AND version=#{expectedVersion} AND applied_command_id IS NULL AND state=#{fromState}")
    int casNextAttempt(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("attemptNo") long attemptNo, @Param("commandId") String commandId,
            @Param("state") String state, @Param("fromState") String fromState, @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);

    /** 绑定活动命令，供来源 commandId 受理。 */
    @Update("UPDATE stock_effect SET active_command_id=#{commandId}, attempt_no=GREATEST(attempt_no, #{attemptNo}), "
            + "state=#{state}, version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND id=#{effectId} AND applied_command_id IS NULL "
            + "AND (active_command_id IS NULL OR active_command_id=#{commandId})")
    int casBindActive(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("attemptNo") long attemptNo,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 过账成功：唯一 applied_command_id。 */
    @Update("UPDATE stock_effect SET applied_command_id=#{commandId}, state='APPLIED', version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId} "
            + "AND applied_command_id IS NULL AND active_command_id=#{commandId}")
    int casApply(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** 取消未过账命令，保留 active 供安全关闭引用。 */
    @Update("UPDATE stock_effect SET active_command_id=#{commandId}, attempt_no=GREATEST(attempt_no, 1), state=#{state}, "
            + "version=version+1, updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND id=#{effectId} AND applied_command_id IS NULL "
            + "AND (active_command_id=#{commandId} OR active_command_id IS NULL)")
    int casCancelActive(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 未过账且非 STARTED/UNKNOWN 时写入安全关闭，下一尝试必须引用该命令。 */
    @Update("UPDATE stock_effect SET active_command_id=#{commandId}, state='SAFE_CLOSED', version=version+1, "
            + "updated_at=#{now} WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{effectId} "
            + "AND applied_command_id IS NULL AND state NOT IN ('STARTED','UNKNOWN','APPLIED')")
    int casSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** 插入执行尝试。 */
    @Insert("INSERT INTO stock_effect_attempt (id, enterprise_id, warehouse_id, effect_id, command_id, previous_command_id, "
            + "attempt_no, state, digest_version, intent_digest, canonical_request, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{effectId}, #{commandId}, #{previousCommandId}, #{attemptNo}, "
            + "#{state}, #{digestVersion}, #{intentDigest}, #{canonicalRequest}, 0, #{now}, #{now})")
    int insertAttempt(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("effectId") String effectId,
            @Param("commandId") String commandId, @Param("previousCommandId") String previousCommandId,
            @Param("attemptNo") long attemptNo, @Param("state") String state, @Param("digestVersion") long digestVersion,
            @Param("intentDigest") String intentDigest, @Param("canonicalRequest") String canonicalRequest,
            @Param("now") Timestamp now);

    /** 读取指定尝试的意图摘要。 */
    @Select("SELECT intent_digest, digest_version, canonical_request, state FROM stock_effect_attempt "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND effect_id=#{effectId} "
            + "AND attempt_no=#{attemptNo}")
    Map<String, Object> getAttempt(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("attemptNo") long attemptNo);

    /** 记录写调用幂等。 */
    @Insert("INSERT INTO write_idempotency (id, enterprise_id, warehouse_id, client_operation_id, request_digest, "
            + "resource_id, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, "
            + "#{clientOperationId}, #{requestDigest}, #{resourceId}, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertIdempotency(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("requestDigest") String requestDigest, @Param("resourceId") String resourceId,
            @Param("now") Timestamp now);

    /** 读取同键首次请求摘要。 */
    @Select("SELECT request_digest, resource_id FROM write_idempotency WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND client_operation_id=#{clientOperationId}")
    Map<String, Object> getIdempotency(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);
}
