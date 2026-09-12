package com.lrj.wms.inventory.jobs;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;

/**
 * 业务 run/shard 协议。领取与提交必须核对 claim_epoch 与 fence_token。
 * 不发明 OQ-03，不走 Seata。
 */
public final class JobRunService {
    public static final String PLANNING = "PLANNING";
    public static final String PLANNED = "PLANNED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String FAILED = "FAILED";
    public static final String READY = "READY";
    public static final String LEASED = "LEASED";
    public static final String QUARANTINED = "QUARANTINED";
    public static final int RECLAIM_LIMIT = 100;

    private final SqlSession session;
    private final Clock clock;

    public JobRunService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 按 runKey 规划分片。同键重放返回已有运行；活跃分片冲突拒绝。 */
    public Map<String, Object> plan(String enterpriseId, String warehouseId, String runKey, String jobType,
            String scopeCode, String windowId, String inputVersion, List<String> shardKeys) {
        require(enterpriseId, warehouseId, runKey, jobType);
        if (shardKeys == null || shardKeys.isEmpty()) {
            throw new JobRunException("INVALID_SHARDS", "至少规划一个分片");
        }
        JobRunMapper jobs = session.getMapper(JobRunMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        String runId = UUID.randomUUID().toString();
        jobs.insertRunIgnore(runId, enterpriseId, warehouseId, runKey, jobType, scopeCode, windowId, inputVersion,
                PLANNING, shardKeys.size(), now);
        Map<String, Object> run = jobs.lockRunByKey(enterpriseId, warehouseId, runKey);
        if (run == null) {
            throw new JobRunException("RESOURCE_NOT_FOUND", "任务运行不存在");
        }
        if (!PLANNING.equals(String.valueOf(run.get("state")))) {
            return body(run, true);
        }
        String persisted = String.valueOf(run.get("id"));
        HashSet<String> existing = new HashSet<>(jobs.listShardKeys(enterpriseId, warehouseId, persisted));
        try {
            for (String shardKey : shardKeys) {
                if (shardKey == null || shardKey.isBlank()) {
                    throw new JobRunException("INVALID_SHARDS", "分片键不能空");
                }
                if (existing.contains(shardKey)) {
                    continue;
                }
                jobs.insertShard(UUID.randomUUID().toString(), enterpriseId, warehouseId, persisted, jobType, shardKey,
                        now);
            }
        } catch (PersistenceException duplicate) {
            throw new JobRunException("DUPLICATE_ACTIVE_SHARD", "活跃分片已存在");
        }
        if (jobs.casRunPlanned(enterpriseId, warehouseId, persisted, PLANNED, shardKeys.size(),
                asLong(run.get("version")), now) != 1) {
            throw new JobRunException("CONFLICT", "规划提交冲突");
        }
        return body(jobs.lockRunByKey(enterpriseId, warehouseId, runKey), false);
    }

    /** 领取一个 READY 分片。 */
    public Map<String, Object> claim(String enterpriseId, String warehouseId, String jobType, String owner,
            Duration lease) {
        require(enterpriseId, warehouseId, jobType, owner);
        JobRunMapper jobs = session.getMapper(JobRunMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> shard = jobs.lockReadyShard(enterpriseId, warehouseId, jobType, now);
        if (shard == null) {
            return Map.of("claimed", false);
        }
        String fence = UUID.randomUUID().toString();
        Timestamp until = Timestamp.from(clock.instant().plus(lease == null ? Duration.ofSeconds(30) : lease));
        if (jobs.casClaim(enterpriseId, warehouseId, String.valueOf(shard.get("id")), owner, until, fence,
                asLong(shard.get("version")), now) != 1) {
            throw new JobRunException("CONFLICT", "领取冲突");
        }
        jobs.bumpRunCounts(enterpriseId, warehouseId, String.valueOf(shard.get("run_id")), 0, 0, RUNNING, now);
        Map<String, Object> after = jobs.lockShard(enterpriseId, warehouseId, String.valueOf(shard.get("id")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claimed", true);
        body.put("shardId", after.get("id"));
        body.put("runId", after.get("run_id"));
        body.put("shardKey", after.get("shard_key"));
        body.put("claimEpoch", after.get("claim_epoch"));
        body.put("fenceToken", after.get("fence_token"));
        body.put("leaseUntil", after.get("lease_until"));
        return body;
    }

    public Map<String, Object> heartbeat(String enterpriseId, String warehouseId, String shardId, long epoch,
            String fence, Duration lease) {
        JobRunMapper jobs = session.getMapper(JobRunMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp until = Timestamp.from(clock.instant().plus(lease == null ? Duration.ofSeconds(30) : lease));
        if (jobs.casHeartbeat(enterpriseId, warehouseId, shardId, epoch, fence, until, now) != 1) {
            throw new JobRunException("STALE_FENCE", "心跳被回收或旧 epoch 拒绝");
        }
        return Map.of("shardId", shardId, "leaseUntil", until);
    }

    public Map<String, Object> checkpoint(String enterpriseId, String warehouseId, String shardId, long epoch,
            String fence, String cursor) {
        JobRunMapper jobs = session.getMapper(JobRunMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        if (jobs.casCheckpoint(enterpriseId, warehouseId, shardId, epoch, fence, cursor, now) != 1) {
            throw new JobRunException("STALE_FENCE", "检查点被回收或旧 epoch 拒绝");
        }
        return Map.of("shardId", shardId, "cursorKey", cursor);
    }

    public Map<String, Object> complete(String enterpriseId, String warehouseId, String shardId, long epoch,
            String fence) {
        return finish(enterpriseId, warehouseId, shardId, epoch, fence, SUCCEEDED, null, 0, 1, 0);
    }

    public Map<String, Object> fail(String enterpriseId, String warehouseId, String shardId, long epoch, String fence,
            String error) {
        return finish(enterpriseId, warehouseId, shardId, epoch, fence, FAILED, error, 1, 0, 1);
    }

    /** 过期租约回收并递增 epoch，旧 fence 立即失效。 */
    public int reclaimExpired(String enterpriseId, String warehouseId) {
        require(enterpriseId, warehouseId, "x", "x");
        JobRunMapper jobs = session.getMapper(JobRunMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        List<Map<String, Object>> expired = jobs.lockExpired(enterpriseId, warehouseId, now, RECLAIM_LIMIT);
        int reclaimed = 0;
        for (Map<String, Object> shard : expired) {
            if (jobs.casReclaim(enterpriseId, warehouseId, String.valueOf(shard.get("id")), asLong(shard.get("version")),
                    now) == 1) {
                reclaimed++;
            }
        }
        return reclaimed;
    }

    private Map<String, Object> finish(String enterpriseId, String warehouseId, String shardId, long epoch, String fence,
            String state, String error, int retryDelta, int succeededDelta, int failedDelta) {
        JobRunMapper jobs = session.getMapper(JobRunMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> shard = jobs.lockShard(enterpriseId, warehouseId, shardId);
        if (shard == null) {
            throw new JobRunException("RESOURCE_NOT_FOUND", "分片不存在");
        }
        if (jobs.casFinish(enterpriseId, warehouseId, shardId, epoch, fence, state, error, retryDelta, now) != 1) {
            throw new JobRunException("STALE_FENCE", "提交被回收或旧 epoch 拒绝");
        }
        Map<String, Object> run = jobs.lockRunById(enterpriseId, warehouseId, String.valueOf(shard.get("run_id")));
        int planned = ((Number) run.get("planned_shards")).intValue();
        int succeeded = ((Number) run.get("succeeded_shards")).intValue() + succeededDelta;
        int failed = ((Number) run.get("failed_shards")).intValue() + failedDelta;
        String runState = succeeded + failed < planned ? RUNNING
                : failed > 0 ? PARTIAL_FAILED : SUCCEEDED;
        jobs.bumpRunCounts(enterpriseId, warehouseId, String.valueOf(run.get("id")), succeededDelta, failedDelta,
                runState, now);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shardId", shardId);
        body.put("state", state);
        body.put("runState", runState);
        return body;
    }

    private static Map<String, Object> body(Map<String, Object> run, boolean replayed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.get("id"));
        body.put("state", run.get("state"));
        body.put("plannedShards", run.get("planned_shards"));
        body.put("replayed", replayed);
        return body;
    }

    private static void require(String enterpriseId, String warehouseId, String jobType, String owner) {
        if (enterpriseId == null || enterpriseId.isBlank() || warehouseId == null || warehouseId.isBlank()
                || jobType == null || jobType.isBlank() || owner == null || owner.isBlank()) {
            throw new JobRunException("INVALID_SCOPE", "任务必须带企业/仓/类型");
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
