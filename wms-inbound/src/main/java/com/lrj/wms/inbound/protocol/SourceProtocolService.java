package com.lrj.wms.inbound.protocol;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 入库 T1/T3。先锁 source_effect；同事实换键复用原命令。 */
public final class SourceProtocolService {
    public static final String SOURCE = "wms-inbound";
    public static final String ACTION_RECEIVE = "RECEIVE";
    public static final String ACTION_PUTAWAY = "PUTAWAY";

    private final SqlSession session;
    private final Clock clock;

    public SourceProtocolService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** T1：保存效果、命令、实物与 Outbox。 */
    public Map<String, Object> submitReceive(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty) {
        return submitReceive(enterpriseId, warehouseId, commandId, parentId, partId, lineId, actorId, qty, null);
    }

    /** T1；previousCommandId 非空表示安全关闭后的下一尝试。 */
    public Map<String, Object> submitReceive(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty, String previousCommandId) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String digest = sha256(ACTION_RECEIVE + '\u001f' + commandId + '\u001f' + qty.toPlainString());
        String effectCandidate = UUID.randomUUID().toString();
        mapper.insertEffect(effectCandidate, enterpriseId, warehouseId, SOURCE, ACTION_RECEIVE, "RECEIPT_PART", parentId,
                partId, lineId, commandId, "REGISTERED", now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, ACTION_RECEIVE, "RECEIPT_PART", parentId,
                partId, lineId);
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> existing = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            return view(existing, effectId);
        }
        if (previousCommandId == null || previousCommandId.isBlank()) {
            if (effect.get("applied_command_id") != null) {
                return view(mapper.getCommand(enterpriseId, warehouseId, String.valueOf(effect.get("applied_command_id"))),
                        effectId);
            }
            Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
            if (latest != null) {
                return view(latest, effectId);
            }
            if (effect.get("active_command_id") != null) {
                return view(mapper.getCommand(enterpriseId, warehouseId, String.valueOf(effect.get("active_command_id"))),
                        effectId);
            }
        }
        long attemptNo = 1L;
        if (previousCommandId != null && !previousCommandId.isBlank()) {
            if (!"SAFE_CLOSED".equals(String.valueOf(effect.get("state")))) {
                throw new IllegalStateException("STALE_EXECUTION_ATTEMPT");
            }
            if (!previousCommandId.equals(String.valueOf(effect.get("active_command_id")))) {
                throw new IllegalStateException("STALE_EXECUTION_ATTEMPT");
            }
            attemptNo = ((Number) effect.get("attempt_no")).longValue() + 1;
            if (mapper.casNextAttempt(enterpriseId, warehouseId, effectId, attemptNo, commandId, "OPEN",
                    "SAFE_CLOSED", ((Number) effect.get("version")).longValue(), now) != 1) {
                throw new IllegalStateException("VERSION_CONFLICT");
            }
        }
        String executionId = UUID.randomUUID().toString();
        String payload = "{\"qty\":\"" + qty.toPlainString() + "\",\"commandId\":\"" + commandId + "\"}";
        mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_RECEIVE,
                attemptNo, blankToNull(previousCommandId), digest, payload, "PENDING", now);
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_RECEIVE, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (previousCommandId == null || previousCommandId.isBlank()) {
            if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, "OPEN", now) != 1) {
                throw new IllegalStateException("VERSION_CONFLICT");
            }
        }
        return view(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId);
    }

    /** T1：上架子动作命令。 */
    public Map<String, Object> submitPutaway(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String digest = sha256(ACTION_PUTAWAY + '\u001f' + commandId + '\u001f' + qty.toPlainString());
        String effectCandidate = UUID.randomUUID().toString();
        mapper.insertEffect(effectCandidate, enterpriseId, warehouseId, SOURCE, ACTION_PUTAWAY, "SUB_ACTION", parentId,
                partId, lineId, commandId, "REGISTERED", now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, ACTION_PUTAWAY, "SUB_ACTION", parentId,
                partId, lineId);
        mapper.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> existing = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            return view(existing, effectId);
        }
        Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
        if (latest != null) {
            return view(latest, effectId);
        }
        String executionId = UUID.randomUUID().toString();
        String payload = "{\"qty\":\"" + qty.toPlainString() + "\",\"commandId\":\"" + commandId + "\"}";
        mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_PUTAWAY, 1L,
                null, digest, payload, "PENDING", now);
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_PUTAWAY, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1L, "OPEN", now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        return view(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId);
    }

    /** 未过账命令安全关闭，之后才允许同一效果的下一尝试。 */
    public Map<String, Object> safeClose(String enterpriseId, String warehouseId, String commandId) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String effectId = mapper.findEffectByCommand(enterpriseId, warehouseId, commandId);
        if (effectId == null) {
            throw new IllegalStateException("RESOURCE_NOT_FOUND");
        }
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, effectId);
        if (effect.get("applied_command_id") != null) {
            throw new IllegalStateException("EFFECT_ALREADY_APPLIED");
        }
        String state = String.valueOf(effect.get("state"));
        if ("STARTED".equals(state) || "UNKNOWN".equals(state)) {
            throw new IllegalStateException("STALE_EXECUTION_ATTEMPT");
        }
        if (mapper.markSafeClose(enterpriseId, warehouseId, commandId, UUID.randomUUID().toString(),
                "{\"commandId\":\"" + commandId + "\"}", now) != 1) {
            throw new IllegalStateException("RESOURCE_NOT_FOUND");
        }
        if (mapper.casSafeClose(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        return view(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId);
    }

    /** T3：inbox + 过账累计。同 eventId 重放不二次加 posted。 */
    public Map<String, Object> consumeResult(String enterpriseId, String warehouseId, String eventId, String commandId,
            String resultState, String postingId, BigDecimal postedQty) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        Map<String, Object> command = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (command == null) {
            throw new IllegalStateException("来源命令不存在");
        }
        int inserted = mapper.insertInbox(eventId, enterpriseId, warehouseId, commandId, "InventoryCommandResult",
                "{\"state\":\"" + resultState + "\",\"postingId\":\"" + postingId + "\"}", now);
        if (inserted == 1) {
            mapper.updateCommandResult(enterpriseId, warehouseId, commandId, resultState, commandId, postingId, now);
            mapper.updateExecutionSync(enterpriseId, warehouseId, commandId, resultState, postedQty, now);
            Object effectKey = command.get("business_effect_key");
            if (effectKey != null) {
                mapper.updateEffectApplied(enterpriseId, warehouseId, String.valueOf(effectKey), commandId, resultState,
                        now);
            }
        }
        Map<String, Object> body = view(mapper.getCommand(enterpriseId, warehouseId, commandId),
                mapper.findEffectByCommand(enterpriseId, warehouseId, commandId));
        body.put("consumed", inserted == 1);
        return body;
    }

    public Map<String, Object> get(String enterpriseId, String warehouseId, String commandId) {
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        Map<String, Object> command = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (command == null) {
            throw new IllegalStateException("来源命令不存在");
        }
        return view(command, mapper.findEffectByCommand(enterpriseId, warehouseId, commandId));
    }

    private static Map<String, Object> view(Map<String, Object> command, String effectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", command.get("command_id"));
        body.put("state", command.get("state"));
        body.put("effectId", effectId);
        body.put("postingId", command.get("posting_id"));
        return body;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
    }
}
