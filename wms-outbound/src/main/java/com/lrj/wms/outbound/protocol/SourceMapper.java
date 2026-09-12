package com.lrj.wms.outbound.protocol;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 出库来源协议表。必须带企业/仓条件。 */
public interface SourceMapper {
    /** T1与关窗使用同一范围锁，时间必须在获得锁后生成。 */
    int ensureWindowGuard(@Param("e") String e,@Param("w") String w);
    Map<String,Object> lockWindowGuard(@Param("e") String e,@Param("w") String w);

    /** 回执绑定来源事实，禁止消息任意指定另一业务行。身份字段创建后不可修改。 */
    Map<String, Object> commandFact(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId);

    /** insertEffect：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertEffect(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("action") String action, @Param("factType") String factType, @Param("factParentId") String factParentId,
            @Param("factPartId") String factPartId, @Param("factLineId") String factLineId, @Param("commandId") String commandId,
            @Param("state") String state, @Param("now") Timestamp now);

    /** findEffectId：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    String findEffectId(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("action") String action, @Param("factType") String factType,
            @Param("factParentId") String factParentId, @Param("factPartId") String factPartId,
            @Param("factLineId") String factLineId);

    /** findEffectByCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    String findEffectByCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);

    /** lockEffect：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockEffect(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId);

    /** casBindActive：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casBindActive(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("attemptNo") long attemptNo,
            @Param("state") String state, @Param("now") Timestamp now);

    /** casNextAttempt：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casNextAttempt(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("attemptNo") long attemptNo, @Param("commandId") String commandId,
            @Param("state") String state, @Param("fromState") String fromState, @Param("expectedVersion") long expectedVersion,
            @Param("now") Timestamp now);

    /** casSafeClose：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("now") Timestamp now);

    /** insertCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("operationId") String operationId,
            @Param("executionId") String executionId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptNo") long attemptNo, @Param("previousCommandId") String previousCommandId,
            @Param("digest") String digest, @Param("payload") String payload, @Param("state") String state,
            @Param("now") Timestamp now);

    /** findLatestCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> findLatestCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("effectId") String effectId);

    /** markSafeClose：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("closeId") String closeId, @Param("evidence") String evidence,
            @Param("now") Timestamp now);

    /** getCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId);

    /** insertExecution：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertExecution(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId, @Param("action") String action,
            @Param("qty") BigDecimal qty, @Param("actorId") String actorId, @Param("now") Timestamp now);

    /** insertOutbox：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertOutbox(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId,
            @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Timestamp now);

    /** insertInbox：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertInbox(@Param("eventId") String eventId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId,
            @Param("eventType") String eventType, @Param("payload") String payload, @Param("now") Timestamp now);

    /** updateCommandResult：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateCommandResult(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("state") String state, @Param("operationId") String operationId,
            @Param("postingId") String postingId, @Param("now") Timestamp now);

    /** updateExecutionSync：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateExecutionSync(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("commandId") String commandId, @Param("syncStatus") String syncStatus, @Param("postedQty") BigDecimal postedQty,
            @Param("now") Timestamp now);

    /** updateEffectApplied：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int updateEffectApplied(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("effectId") String effectId, @Param("commandId") String commandId, @Param("state") String state,
            @Param("now") Timestamp now);
    /** 回执在效果锁之后锁命令，跨不同eventId只允许一次终态生效。 */
    Map<String, Object> lockCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("commandId") String commandId);
}
