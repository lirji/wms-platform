package com.lrj.wms.inventory.jobs;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 任务运行与分片。条件更新必须带 epoch/fence。 */
public interface JobRunMapper {
    @Insert("INSERT INTO job_run (id, enterprise_id, warehouse_id, run_key, job_type, scope_code, window_id, "
            + "input_version, state, planned_shards, succeeded_shards, failed_shards, version, created_at, updated_at) "
            + "VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{runKey}, #{jobType}, #{scopeCode}, #{windowId}, "
            + "#{inputVersion}, #{state}, #{plannedShards}, 0, 0, 0, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE updated_at=updated_at")
    int insertRunIgnore(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("runKey") String runKey, @Param("jobType") String jobType,
            @Param("scopeCode") String scopeCode, @Param("windowId") String windowId,
            @Param("inputVersion") String inputVersion, @Param("state") String state,
            @Param("plannedShards") int plannedShards, @Param("now") Timestamp now);

    @Select("SELECT id, run_key, job_type, state, planned_shards, succeeded_shards, failed_shards, version "
            + "FROM job_run WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND run_key=#{runKey} "
            + "FOR UPDATE")
    Map<String, Object> lockRunByKey(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runKey") String runKey);

    @Select("SELECT id, run_key, job_type, state, planned_shards, succeeded_shards, failed_shards, version "
            + "FROM job_run WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{runId} FOR UPDATE")
    Map<String, Object> lockRunById(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId);

    @Update("UPDATE job_run SET state=#{state}, planned_shards=#{plannedShards}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{runId} AND version=#{version}")
    int casRunPlanned(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId, @Param("state") String state, @Param("plannedShards") int plannedShards,
            @Param("version") long version, @Param("now") Timestamp now);

    @Update("UPDATE job_run SET succeeded_shards=succeeded_shards+#{succeededDelta}, failed_shards=failed_shards+#{failedDelta}, "
            + "state=#{state}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{runId}")
    int bumpRunCounts(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId, @Param("succeededDelta") int succeededDelta,
            @Param("failedDelta") int failedDelta, @Param("state") String state, @Param("now") Timestamp now);

    @Select("SELECT shard_key FROM job_shard WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "AND run_id=#{runId}")
    List<String> listShardKeys(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("runId") String runId);

    @Insert("INSERT INTO job_shard (id, enterprise_id, warehouse_id, run_id, job_type, shard_key, state, claim_epoch, "
            + "retry_count, version, created_at, updated_at) VALUES (#{id}, #{enterpriseId}, #{warehouseId}, #{runId}, "
            + "#{jobType}, #{shardKey}, 'READY', 0, 0, 0, #{now}, #{now})")
    int insertShard(@Param("id") String id, @Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("runId") String runId, @Param("jobType") String jobType,
            @Param("shardKey") String shardKey, @Param("now") Timestamp now);

    @Select("SELECT id, run_id, job_type, shard_key, state, lease_owner, lease_until, claim_epoch, fence_token, "
            + "cursor_key, retry_count, version FROM job_shard WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND id=#{shardId} FOR UPDATE")
    Map<String, Object> lockShard(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId);

    @Select("SELECT id, run_id, job_type, shard_key, state, lease_owner, lease_until, claim_epoch, fence_token, "
            + "cursor_key, retry_count, version FROM job_shard WHERE enterprise_id=#{enterpriseId} "
            + "AND warehouse_id=#{warehouseId} AND job_type=#{jobType} AND state='READY' "
            + "AND (next_retry_at IS NULL OR next_retry_at<=#{now}) ORDER BY id LIMIT 1 FOR UPDATE")
    Map<String, Object> lockReadyShard(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("jobType") String jobType, @Param("now") Timestamp now);

    @Update("UPDATE job_shard SET state='LEASED', lease_owner=#{owner}, lease_until=#{leaseUntil}, "
            + "claim_epoch=claim_epoch+1, fence_token=#{fence}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{shardId} "
            + "AND version=#{version} AND state='READY'")
    int casClaim(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("owner") String owner, @Param("leaseUntil") Timestamp leaseUntil,
            @Param("fence") String fence, @Param("version") long version, @Param("now") Timestamp now);

    @Update("UPDATE job_shard SET lease_until=#{leaseUntil}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{shardId} "
            + "AND claim_epoch=#{epoch} AND fence_token=#{fence} AND state='LEASED'")
    int casHeartbeat(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("epoch") long epoch, @Param("fence") String fence,
            @Param("leaseUntil") Timestamp leaseUntil, @Param("now") Timestamp now);

    @Update("UPDATE job_shard SET cursor_key=#{cursor}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{shardId} "
            + "AND claim_epoch=#{epoch} AND fence_token=#{fence} AND state='LEASED'")
    int casCheckpoint(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("epoch") long epoch, @Param("fence") String fence,
            @Param("cursor") String cursor, @Param("now") Timestamp now);

    @Update("UPDATE job_shard SET state=#{state}, lease_owner=NULL, lease_until=NULL, fence_token=NULL, "
            + "last_error=#{error}, retry_count=retry_count+#{retryDelta}, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{shardId} "
            + "AND claim_epoch=#{epoch} AND fence_token=#{fence} AND state='LEASED'")
    int casFinish(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("epoch") long epoch, @Param("fence") String fence,
            @Param("state") String state, @Param("error") String error, @Param("retryDelta") int retryDelta,
            @Param("now") Timestamp now);

    @Select("SELECT id, run_id, claim_epoch, fence_token, version FROM job_shard "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND state='LEASED' "
            + "AND lease_until<#{now} ORDER BY id LIMIT #{limit} FOR UPDATE")
    List<Map<String, Object>> lockExpired(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("now") Timestamp now, @Param("limit") int limit);

    @Update("UPDATE job_shard SET state='READY', lease_owner=NULL, lease_until=NULL, fence_token=NULL, "
            + "claim_epoch=claim_epoch+1, version=version+1, updated_at=#{now} "
            + "WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND id=#{shardId} "
            + "AND version=#{version} AND state='LEASED'")
    int casReclaim(@Param("enterpriseId") String enterpriseId, @Param("warehouseId") String warehouseId,
            @Param("shardId") String shardId, @Param("version") long version, @Param("now") Timestamp now);

    @Select("SELECT id, run_key, job_type, state, planned_shards, succeeded_shards, failed_shards, version, created_at "
            + "FROM job_run WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> listRuns(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("limit") int limit);

    @Select("SELECT id, shard_key, state, lease_owner, lease_until, claim_epoch, last_error "
            + "FROM job_shard WHERE enterprise_id=#{enterpriseId} AND warehouse_id=#{warehouseId} AND run_id=#{runId} "
            + "ORDER BY shard_key")
    List<Map<String, Object>> listShards(@Param("enterpriseId") String enterpriseId,
            @Param("warehouseId") String warehouseId, @Param("runId") String runId);
}
