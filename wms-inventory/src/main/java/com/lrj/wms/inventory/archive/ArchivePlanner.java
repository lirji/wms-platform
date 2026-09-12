package com.lrj.wms.inventory.archive;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.inventory.domain.ExpiryPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 只生成可复核候选清单。没有统一保留期，导出确认、删除及墓碑治理是独立授权流程。 */
public final class ArchivePlanner {
    public static final int PAGE_SIZE = 200;
    private final SqlSession session;
    private final Clock clock;
    public ArchivePlanner(SqlSession session, Clock clock) { this.session = session; this.clock = clock; }

    /** 单次最多 200 行，窗口行锁保证并发安全，候选/摘要/游标共用事务，宕机后同键续跑。 */
    public Map<String, Object> execute(String enterprise, String warehouse, String run, Instant cutoff, String policy, String actor) {
        require(enterprise,64); require(warehouse,64); require(run,64); require(policy,256); require(actor,128);
        if (cutoff == null || !cutoff.isBefore(clock.instant())) throw new JobRunException("INVALID_CUTOFF", "归档规划必须提供过去的明确关闭时刻");
        ArchivePlanMapper mapper = session.getMapper(ArchivePlanMapper.class);
        Timestamp now = Timestamp.from(clock.instant());
        mapper.create(enterprise,warehouse,run,policy,Timestamp.from(cutoff),actor,UUID.randomUUID().toString(),now);
        var plan = mapper.lock(enterprise,warehouse,run);
        if (!cutoff.equals(ExpiryPolicy.instantOf(plan.get("cutoff_at"))) || !policy.equals(plan.get("policy_ref"))) {
            throw new JobRunException("ARCHIVE_PLAN_CONFLICT", "同运行键不能改变关闭时刻或保留依据");
        }
        if ("PLANNED_EXPORT".equals(plan.get("state"))) return body(plan);
        var rows = mapper.candidates(enterprise,warehouse,Timestamp.from(cutoff), (String) plan.get("cursor_id"));
        String hash = String.valueOf(plan.get("manifest_hash"));
        String cursor = (String) plan.get("cursor_id");
        int count = 0;
        for (var row : rows.stream().limit(PAGE_SIZE).toList()) {
            String ledger = String.valueOf(row.get("id")), payload = String.valueOf(row.get("payload_hash"));
            if (mapper.item(enterprise,warehouse,String.valueOf(plan.get("id")),UUID.randomUUID().toString(),ledger,payload,now) != 1)
                throw new JobRunException("ARCHIVE_PLAN_CONFLICT", "候选清单与检查点不一致");
            hash = digest(hash + "\n" + ledger + "\n" + payload);
            cursor = ledger; count++;
        }
        String state = rows.size() <= PAGE_SIZE ? "PLANNED_EXPORT" : "PLANNING";
        if (mapper.checkpoint(enterprise,warehouse,String.valueOf(plan.get("id")), ((Number)plan.get("version")).longValue(),
                cursor,count,hash,state,now) != 1) throw new JobRunException("CONFLICT", "归档规划检查点冲突");
        return body(mapper.lock(enterprise,warehouse,run));
    }
    private static Map<String, Object> body(Map<String, Object> plan) {
        return Map.of("planId",plan.get("id"),"state",plan.get("state"),"candidateCount",plan.get("candidate_count"),
                "manifestHash",plan.get("manifest_hash"),"exported",false,"deleted",false);
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(String value,int limit) {
        if(value==null || value.isBlank() || value.length()>limit) throw new JobRunException("INVALID_ARCHIVE_PLAN", "必须提供有界企业/仓/运行键/保留依据/操作者");
    }
}
