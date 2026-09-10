package com.lrj.wms.inventory.effect;

import com.lrj.wms.inventory.effect.domain.EffectCodes;
import com.lrj.wms.inventory.effect.domain.EffectProtocolException;
import com.lrj.wms.inventory.effect.domain.LegacyIdentityAdapter;
import com.lrj.wms.inventory.effect.domain.RequestDigest;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 效果身份登记、查询与安全重授权；不实现库存过账。 */
public final class EffectService {
    private final SqlSession session;
    private final Clock clock;

    public EffectService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** 按权威事实分配或恢复不透明 effectId；换客户端键不得产生新事实。 */
    public Map<String, Object> register(String enterpriseId, String warehouseId, String action, String factType,
            String factParentId, String factPartId, String factLineId, String clientOperationId) {
        LegacyIdentityAdapter.FactKey facts = LegacyIdentityAdapter.requireRecoverableFacts(action, factType,
                factParentId, factPartId, factLineId);
        String requestDigest = RequestDigest.digest(RequestDigest.VERSION_1, facts.action(), facts.factType(),
                facts.factParentId(), facts.factPartId(), facts.factLineId(), null, null);
        EffectMapper mapper = mapper();
        Map<String, Object> existingKey = mapper.getIdempotency(enterpriseId, warehouseId, clientOperationId);
        if (existingKey != null) {
            if (!requestDigest.equals(String.valueOf(existingKey.get("request_digest")))) {
                throw new EffectProtocolException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键异内容拒绝");
            }
            return get(enterpriseId, warehouseId, String.valueOf(existingKey.get("resource_id")));
        }
        String candidate = UUID.randomUUID().toString();
        Timestamp now = now();
        mapper.insertEffect(candidate, enterpriseId, warehouseId, EffectCodes.SOURCE_INVENTORY, facts.action(),
                facts.factType(), facts.factParentId(), facts.factPartId(), facts.factLineId(),
                EffectCodes.STATE_REGISTERED, now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, EffectCodes.SOURCE_INVENTORY, facts.action(),
                facts.factType(), facts.factParentId(), facts.factPartId(), facts.factLineId());
        mapper.insertIdempotency(UUID.randomUUID().toString(), enterpriseId, warehouseId, clientOperationId, requestDigest,
                effectId, now);
        Map<String, Object> replay = mapper.getIdempotency(enterpriseId, warehouseId, clientOperationId);
        if (replay != null && !requestDigest.equals(String.valueOf(replay.get("request_digest")))) {
            throw new EffectProtocolException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键异内容拒绝");
        }
        return get(enterpriseId, warehouseId, effectId);
    }

    /** 查询效果；safeToRetry 只由服务端状态计算。 */
    public Map<String, Object> get(String enterpriseId, String warehouseId, String effectId) {
        Map<String, Object> row = mapper().getEffect(enterpriseId, warehouseId, EffectCodes.requireId("效果标识", effectId));
        if (row == null) {
            throw new EffectProtocolException("RESOURCE_NOT_FOUND", "效果不存在");
        }
        return view(row);
    }

    /**
     * 首次授权或安全关闭后的下一尝试。STARTED/OPEN 拒绝；UNKNOWN 返回待恢复且不发新号。
     */
    public Map<String, Object> createAttempt(String enterpriseId, String warehouseId, String effectId,
            String previousCommandId, long expectedVersion, Long digestVersion, String clientOperationId) {
        EffectMapper mapper = mapper();
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, EffectCodes.requireId("效果标识", effectId));
        if (effect == null) {
            throw new EffectProtocolException("RESOURCE_NOT_FOUND", "效果不存在");
        }
        if (effect.get("applied_command_id") != null) {
            throw new EffectProtocolException("EFFECT_ALREADY_APPLIED", "已过账效果不得重新授权");
        }
        String state = String.valueOf(effect.get("state"));
        if (EffectCodes.STATE_UNKNOWN.equals(state)) {
            Map<String, Object> pending = new LinkedHashMap<>(view(effect));
            pending.put("status", "RECOVERY_PENDING");
            pending.put("safeToRetry", false);
            pending.put("safeToRetryReason", "结果未知，保持原尝试");
            return pending;
        }
        if (!EffectCodes.allowsNewAttempt(state)) {
            throw new EffectProtocolException("STALE_EXECUTION_ATTEMPT", "当前尝试未安全关闭，不得重做");
        }
        if (longValue(effect.get("version")) != expectedVersion) {
            throw new EffectProtocolException("VERSION_CONFLICT", "效果版本不匹配");
        }
        long nextNo = longValue(effect.get("attempt_no")) + 1;
        Object active = effect.get("active_command_id");
        if (nextNo == 1 && previousCommandId != null && !previousCommandId.isBlank()) {
            throw new EffectProtocolException("STALE_EXECUTION_ATTEMPT", "首次尝试不得携带上一命令");
        }
        if (nextNo > 1 && (previousCommandId == null || !previousCommandId.equals(String.valueOf(active)))) {
            throw new EffectProtocolException("STALE_EXECUTION_ATTEMPT", "必须引用已安全关闭的上一命令");
        }
        long version = digestVersion == null ? RequestDigest.VERSION_1 : digestVersion;
        String canonical = RequestDigest.canonical(version, String.valueOf(effect.get("action")),
                String.valueOf(effect.get("fact_type")), String.valueOf(effect.get("fact_parent_id")),
                String.valueOf(effect.get("fact_part_id")), String.valueOf(effect.get("fact_line_id")), null, null);
        String digest = RequestDigest.digest(version, String.valueOf(effect.get("action")),
                String.valueOf(effect.get("fact_type")), String.valueOf(effect.get("fact_parent_id")),
                String.valueOf(effect.get("fact_part_id")), String.valueOf(effect.get("fact_line_id")), null, null);
        Map<String, Object> existingKey = mapper.getIdempotency(enterpriseId, warehouseId, clientOperationId);
        if (existingKey != null) {
            if (!digest.equals(String.valueOf(existingKey.get("request_digest")))) {
                throw new EffectProtocolException("IDEMPOTENCY_PAYLOAD_MISMATCH", "同键异内容拒绝");
            }
            Map<String, Object> replay = new LinkedHashMap<>(view(effect));
            replay.put("executionAttemptId", existingKey.get("resource_id"));
            replay.put("status", "ACCEPTED");
            return replay;
        }
        String attemptId = UUID.randomUUID().toString();
        String commandId = UUID.randomUUID().toString();
        Timestamp now = now();
        int updated = mapper.casNextAttempt(enterpriseId, warehouseId, effectId, nextNo, commandId, EffectCodes.STATE_OPEN,
                state, expectedVersion, now);
        if (updated != 1) {
            throw new EffectProtocolException("VERSION_CONFLICT", "并发发放尝试失败");
        }
        mapper.insertAttempt(attemptId, enterpriseId, warehouseId, effectId, commandId, blankToNull(previousCommandId),
                nextNo, EffectCodes.STATE_OPEN, version, digest, canonical, now);
        mapper.insertIdempotency(UUID.randomUUID().toString(), enterpriseId, warehouseId, clientOperationId, digest,
                attemptId, now);
        Map<String, Object> accepted = new LinkedHashMap<>(get(enterpriseId, warehouseId, effectId));
        accepted.put("executionAttemptId", attemptId);
        accepted.put("commandId", commandId);
        accepted.put("status", "ACCEPTED");
        return accepted;
    }

    /** 用原 digestVersion 重放已保存规范化请求，证明新版本默认值不能改写旧摘要。 */
    public boolean replayMatchesStored(String enterpriseId, String warehouseId, String effectId, long attemptNo) {
        Map<String, Object> attempt = mapper().getAttempt(enterpriseId, warehouseId, effectId, attemptNo);
        if (attempt == null) {
            return false;
        }
        long version = longValue(attempt.get("digest_version"));
        String canonical = String.valueOf(attempt.get("canonical_request"));
        String expected = String.valueOf(attempt.get("intent_digest"));
        return expected.equals(RequestDigest.digest(version, split(canonical, 0), split(canonical, 1), split(canonical, 2),
                split(canonical, 3), split(canonical, 4), version >= RequestDigest.VERSION_2 ? split(canonical, 5) : null,
                version >= RequestDigest.VERSION_2 ? split(canonical, 6) : null));
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", row.get("id"));
        body.put("version", longValue(row.get("version")));
        body.put("status", row.get("state"));
        body.put("businessEffectKey", row.get("business_effect_key"));
        body.put("activeCommandId", row.get("active_command_id"));
        body.put("appliedCommandId", row.get("applied_command_id"));
        body.put("attemptNo", longValue(row.get("attempt_no")));
        String state = String.valueOf(row.get("state"));
        boolean safe = row.get("applied_command_id") == null && EffectCodes.allowsNewAttempt(state);
        body.put("safeToRetry", safe);
        body.put("safeToRetryReason", safe ? "允许登记或安全关闭后的下一尝试" : "当前状态不可重做");
        return body;
    }

    private EffectMapper mapper() {
        return session.getMapper(EffectMapper.class);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static String split(String canonical, int index) {
        String[] parts = canonical.split("\u001f", -1);
        return index < parts.length ? parts[index] : "";
    }
}
