package com.lrj.wms.inventory.effect.infrastructure;

import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 效果身份与尝试的仓内读写；调用方必须带企业/仓条件。 */
public interface EffectMapper {
    /** 按权威事实登记效果；冲突时保持原行。 */
    /** insertEffect：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertEffect(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("action") String action, @Param("factType") String factType, @Param("factParentId") String factParentId,
            @Param("factPartId") String factPartId, @Param("factLineId") String factLineId, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 按权威事实读取已有不透明身份。 */
    /** findEffectId：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    String findEffectId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("action") String action, @Param("factType") String factType,
            @Param("factParentId") String factParentId, @Param("factPartId") String factPartId,
            @Param("factLineId") String factLineId);

    /** 按不透明身份读取效果行。 */
    /** lockEffect：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockEffect(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId);

    /** 无锁读取，供查询接口。 */
    /** getEffect：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getEffect(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId);

    /** CAS 发放下一尝试号并切换活动命令。 */
    /** casNextAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casNextAttempt(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("attemptNo") long attemptNo, @Param("commandId") String commandId,
            @Param("state") String state, @Param("fromState") String fromState, @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);

    /** 绑定活动命令，供来源 commandId 受理。 */
    /** casBindActive：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casBindActive(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("attemptNo") long attemptNo,
            @Param("state") String state, @Param("now") Timestamp now);

    /** 过账成功：唯一 applied_command_id。 */
    /** casApply：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casApply(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** 取消未过账命令，保留 active 供安全关闭引用。 */
    /** casCancelActive：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casCancelActive(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("state") String state,
            @Param("now") Timestamp now);

    /** 未过账且非 STARTED/UNKNOWN 时写入安全关闭，下一尝试必须引用该命令。 */
    /** casSafeClose：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** 插入执行尝试。 */
    /** insertAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertAttempt(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("effectId") String effectId,
            @Param("commandId") String commandId, @Param("previousCommandId") String previousCommandId,
            @Param("attemptNo") long attemptNo, @Param("state") String state, @Param("digestVersion") long digestVersion,
            @Param("intentDigest") String intentDigest, @Param("canonicalRequest") String canonicalRequest,
            @Param("now") Timestamp now);

    /** 读取指定尝试的意图摘要。 */
    /** getAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getAttempt(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("attemptNo") long attemptNo);

    /** 记录写调用幂等。 */
    /** insertIdempotency：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIdempotency(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("clientOperationId") String clientOperationId,
            @Param("requestDigest") String requestDigest, @Param("resourceId") String resourceId,
            @Param("now") Timestamp now);

    /** 读取同键首次请求摘要。 */
    /** getIdempotency：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getIdempotency(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("clientOperationId") String clientOperationId);
}
