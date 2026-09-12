package com.lrj.wms.inventory.inventory.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 库存命令、凭证与授权。调用方必须带企业/仓条件。 */
public interface StockCommandMapper {
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

    /** insertPosting：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertPosting(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("sourceService") String sourceService,
            @Param("commandId") String commandId, @Param("effectId") String effectId, @Param("action") String action,
            @Param("attemptId") String attemptId, @Param("postingType") String postingType, @Param("quantity") BigDecimal quantity,
            @Param("sourceExecutionId") String sourceExecutionId, @Param("sourceDocumentId") String sourceDocumentId,
            @Param("manifest") String manifest, @Param("now") Timestamp now);

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
