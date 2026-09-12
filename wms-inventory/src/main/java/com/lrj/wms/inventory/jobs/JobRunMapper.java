package com.lrj.wms.inventory.jobs;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 任务运行与分片。条件更新必须带 epoch/fence。 */
public interface JobRunMapper {
    /** insertRunIgnore：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertRunIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("runKey") String runKey, @Param("jobType") String jobType,
            @Param("scopeCode") String scopeCode, @Param("windowId") String windowId,
            @Param("inputVersion") String inputVersion, @Param("state") String state,
            @Param("plannedShards") int plannedShards, @Param("now") Timestamp now);

    /** lockRunByKey：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockRunByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runKey") String runKey);

    /** lockRunById：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockRunById(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId);

    /** casRunPlanned：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casRunPlanned(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId, @Param("state") String state, @Param("plannedShards") int plannedShards,
            @Param("version") long version, @Param("now") Timestamp now);

    /** bumpRunCounts：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int bumpRunCounts(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId, @Param("succeededDelta") int succeededDelta,
            @Param("failedDelta") int failedDelta, @Param("state") String state, @Param("now") Timestamp now);

    /** listShardKeys：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<String> listShardKeys(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId);

    /** insertShard：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int insertShard(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("runId") String runId, @Param("jobType") String jobType,
            @Param("shardKey") String shardKey, @Param("now") Timestamp now);

    /** lockShard：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockShard(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId);

    /** lockReadyShard：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    Map<String, Object> lockReadyShard(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("jobType") String jobType, @Param("now") Timestamp now);

    /** casClaim：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casClaim(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("owner") String owner, @Param("leaseUntil") Timestamp leaseUntil,
            @Param("fence") String fence, @Param("version") long version, @Param("now") Timestamp now);

    /** casHeartbeat：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casHeartbeat(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("epoch") long epoch, @Param("fence") String fence,
            @Param("leaseUntil") Timestamp leaseUntil, @Param("now") Timestamp now);

    /** casCheckpoint：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("epoch") long epoch, @Param("fence") String fence,
            @Param("cursor") String cursor, @Param("now") Timestamp now);

    /** casFinish：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casFinish(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("epoch") long epoch, @Param("fence") String fence,
            @Param("state") String state, @Param("error") String error, @Param("retryDelta") int retryDelta,
            @Param("now") Timestamp now);

    /** lockExpired：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> lockExpired(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("now") Timestamp now, @Param("limit") int limit);

    /** casReclaim：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    int casReclaim(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("version") long version, @Param("now") Timestamp now);

    /** listRuns：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    /** 兼容内部首屏读取，仍强制页大小边界。 */
    default List<Map<String, Object>> listRuns(String enterpriseId, String warehouseId, int limit) {
        return listRunsPage(enterpriseId, warehouseId, com.lrj.wms.runtime.web.CursorPage.parse(limit, null, "internal"))
                .stream().limit(limit).toList();
    }

    /** 同时间戳以主键打破平局，数据库最多读取一页加一条。 */
    List<Map<String, Object>> listRunsPage(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId, @Param("page") com.lrj.wms.runtime.web.CursorPage page);

    /** listShards：SQL 定义在同名 Mapper XML，调用方负责用例事务。 */
    List<Map<String, Object>> listShards(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("runId") String runId);
}
