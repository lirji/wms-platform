package com.lrj.wms.inventory.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存命令、凭证与授权。调用方必须带企业/仓条件。 */
public interface StockCommandMapper {
    /** 可靠回执从已提交凭证恢复原始数量与执行身份，不按本次重投请求伪造凭证。 */
    Map<String, Object> postingByCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId);

    /** insertIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertIgnore(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId, @Param("action") String action,
            @Param("effectId") String effectId, @Param("attemptId") String attemptId, @Param("attemptNo") long attemptNo,
            @Param("previousCommandId") String previousCommandId, @Param("digest") String digest,
            @Param("digestVersion") int digestVersion, @Param("state") String state, @Param("now") Timestamp now);

    /** lockByCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockByCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId);

    /** getByCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> getByCommand(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId);

    /** findLatestByEffect：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> findLatestByEffect(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("effectId") String effectId, @Param("action") String action);

    /** markSafeClose：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int markSafeClose(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId,
            @Param("closeId") String closeId, @Param("evidence") String evidence, @Param("now") Timestamp now);

    /** insertCompensationPosting：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertCompensationPosting(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptId") String attemptId, @Param("postingType") String postingType,
            @Param("quantity") BigDecimal quantity, @Param("sourceExecutionId") String sourceExecutionId,
            @Param("sourceDocumentId") String sourceDocumentId, @Param("manifest") String manifest,
            @Param("originalPostingId") String originalPostingId, @Param("now") Timestamp now);

    /** casState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId,
            @Param("fromState") String fromState, @Param("toState") String toState, @Param("resultJson") String resultJson,
            @Param("now") Timestamp now);

    /** 写入前持有历史范围共享锁，旧时间事实不能在关窗完成后迟提交。 */
    default int insertPosting(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptId") String attemptId, @Param("postingType") String postingType, @Param("quantity") BigDecimal quantity,
            @Param("sourceExecutionId") String sourceExecutionId, @Param("sourceDocumentId") String sourceDocumentId,
            @Param("manifest") String manifest, @Param("now") Timestamp now) {
        var guard=lockHistoryGuard(enterpriseId,warehouseId);
        if(guard==null) {
            ensureHistoryGuard(java.util.UUID.randomUUID().toString(),enterpriseId,warehouseId);
            guard=lockHistoryGuard(enterpriseId,warehouseId);
        }
        if(guard==null) throw new IllegalStateException("HISTORY_GUARD_MISSING");
        if(guard.get("closed_before")!=null && now.toInstant().isBefore(com.lrj.wms.runtime.db.DatabaseInstants.require(guard.get("closed_before"))))
            throw new com.lrj.wms.inventory.inventory.InventoryException("HISTORY_WINDOW_CLOSED", "旧时间事实不能写入已冻结历史窗口");
        return appendPosting(id,enterpriseId,warehouseId,sourceService,commandId,effectId,action,attemptId,postingType,quantity,sourceExecutionId,sourceDocumentId,manifest,now);
    }

    /** 仅供上方受控写入口调用，SQL集中在XML，业务不能绕过历史屏障。 */
    int appendPosting(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptId") String attemptId, @Param("postingType") String postingType, @Param("quantity") BigDecimal quantity,
            @Param("sourceExecutionId") String sourceExecutionId, @Param("sourceDocumentId") String sourceDocumentId,
            @Param("manifest") String manifest, @Param("now") Timestamp now);
    /** 首次建范围行允许唯一约束解决竞争，已有范围直接走共享锁。 */
    int ensureHistoryGuard(@Param("guardId") String guardId,@Param("enterpriseId") String enterpriseId,@Param("warehouseId") String warehouseId);
    /** 与关窗排他锁互斥，同一仓的普通写事务之间仍可并发。 */
    Map<String,Object> lockHistoryGuard(@Param("enterpriseId") String enterpriseId,@Param("warehouseId") String warehouseId);


    /** insertPermit：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPermit(@Param("permitId") String permitId, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("attemptId") String attemptId,
            @Param("attemptNo") long attemptNo, @Param("taskId") String taskId, @Param("taskEpoch") long taskEpoch,
            @Param("action") String action, @Param("digest") String digest, @Param("quantity") BigDecimal quantity,
            @Param("actualQty") BigDecimal actualQty, @Param("notExecutedQty") BigDecimal notExecutedQty,
            @Param("state") String state, @Param("startedAt") Timestamp startedAt, @Param("postedAt") Timestamp postedAt,
            @Param("now") Timestamp now);

    /** lockPermitByCommand：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockPermitByCommand(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId);

    /** casPermitState：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casPermitState(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("sourceService") String sourceService, @Param("commandId") String commandId,
            @Param("fromState") String fromState, @Param("toState") String toState, @Param("now") Timestamp now);

    /** lockPosting：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockPosting(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id);

    /** addReversed：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int addReversed(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("id") String id, @Param("qty") BigDecimal qty, @Param("now") Timestamp now);
}
