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
    public static final String ACTION_QUALITY = "QUALITY";

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
            return submission(existing, effectId, ACTION_RECEIVE, qty, true);
        }
        if (previousCommandId == null || previousCommandId.isBlank()) {
            if (effect.get("applied_command_id") != null) {
                return submission(mapper.getCommand(enterpriseId, warehouseId, String.valueOf(effect.get("applied_command_id"))), effectId, ACTION_RECEIVE, qty, true);
            }
            Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
            if (latest != null) {
                return submission(latest, effectId, ACTION_RECEIVE, qty, true);
            }
            if (effect.get("active_command_id") != null) {
                return submission(mapper.getCommand(enterpriseId, warehouseId, String.valueOf(effect.get("active_command_id"))), effectId, ACTION_RECEIVE, qty, true);
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
            com.lrj.wms.runtime.command.CommandReplay.requireSamePayload(
                    mapper.getCommand(enterpriseId, warehouseId, previousCommandId), ACTION_RECEIVE, effectId, qty);
            attemptNo = ((Number) effect.get("attempt_no")).longValue() + 1;
            if (mapper.casNextAttempt(enterpriseId, warehouseId, effectId, attemptNo, commandId, "OPEN",
                    "SAFE_CLOSED", ((Number) effect.get("version")).longValue(), now) != 1) {
                throw new IllegalStateException("VERSION_CONFLICT");
            }
        }
        String executionId = UUID.randomUUID().toString();
        String payload = com.lrj.wms.runtime.command.CommandReplay.payload(commandId, qty);
        if (mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_RECEIVE,
                attemptNo, blankToNull(previousCommandId), digest, payload, "PENDING", now) != 1) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_RECEIVE, qty, true);
        }
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_RECEIVE, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (previousCommandId == null || previousCommandId.isBlank()) {
            if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, attemptNo, "OPEN", now) != 1) {
                throw new IllegalStateException("VERSION_CONFLICT");
            }
        }
        return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_RECEIVE, qty, false);
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
            return submission(existing, effectId, ACTION_PUTAWAY, qty, true);
        }
        Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
        if (latest != null) {
            return submission(latest, effectId, ACTION_PUTAWAY, qty, true);
        }
        String executionId = UUID.randomUUID().toString();
        String payload = com.lrj.wms.runtime.command.CommandReplay.payload(commandId, qty);
        if (mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_PUTAWAY, 1L,
                null, digest, payload, "PENDING", now) != 1) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_PUTAWAY, qty, true);
        }
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_PUTAWAY, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1L, "OPEN", now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_PUTAWAY, qty, false);
    }

    /** T1：分批质检版本命令，结论与来源Outbox同事务固定。 */
    public Map<String, Object> submitQuality(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, com.lrj.wms.contract.messaging.ReceiptQualityDecision decision) {
        BigDecimal qty = decision.inspectedQty();
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String digest = sha256(ACTION_QUALITY + '\u001f' + commandId + '\u001f' + qty.toPlainString());
        String effectCandidate = UUID.randomUUID().toString();
        mapper.insertEffect(effectCandidate, enterpriseId, warehouseId, SOURCE, ACTION_QUALITY, "SUB_ACTION", parentId,
                partId, lineId, commandId, "REGISTERED", now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, ACTION_QUALITY, "SUB_ACTION", parentId,
                partId, lineId);
        mapper.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> existing = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            return submission(existing, effectId, ACTION_QUALITY, qty, true);
        }
        Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
        if (latest != null) {
            return submission(latest, effectId, ACTION_QUALITY, qty, true);
        }
        String executionId = UUID.randomUUID().toString();
        var body = com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.createObjectNode();
        body.put("commandId", commandId); body.put("qty", qty);
        body.set("qualityDecision", com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.valueToTree(decision));
        String payload = body.toString();
        if (mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_QUALITY, 1L,
                null, digest, payload, "PENDING", now) != 1) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_QUALITY, qty, true);
        }
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_QUALITY, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1L, "OPEN", now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_QUALITY, qty, false);
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

    /** 先核验不可变命令事实，再由用例锁业务行；T1/T3都遵循业务行→效果的锁顺序。 */
    public void requireResultFact(String enterpriseId, String warehouseId, String commandId, String action, String lineId) {
        var fact = session.getMapper(SourceMapper.class).commandFact(enterpriseId, warehouseId, commandId);
        if (fact == null || !action.equals(fact.get("action")) || !lineId.equals(fact.get("fact_line_id"))) {
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("RESULT_FACT_MISMATCH");
        }
    }

    /** T3：命令终态和事件身份共同去重，业务用例与回执状态在同一事务提交。 */
    public Map<String, Object> consumeResult(String enterpriseId, String warehouseId, String eventId, String commandId,
            String resultState, String postingId, BigDecimal postedQty) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        Map<String, Object> command = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (command == null) {
            throw new IllegalStateException("来源命令不存在");
        }
        if (!java.util.Set.of("APPLIED", "REJECTED", "CANCELLED", "UNKNOWN").contains(resultState)
                || postedQty == null || postedQty.signum() < 0 || !"APPLIED".equals(resultState) && postedQty.signum() != 0) {
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("INVALID_COMMAND_RESULT");
        }
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, String.valueOf(command.get("business_effect_key")));
        command = mapper.lockCommand(enterpriseId, warehouseId, commandId);
        if ("APPLIED".equals(resultState)) {
            if (postingId == null || postingId.isBlank()
                    || com.lrj.wms.runtime.command.CommandReplay.quantity(command).compareTo(postedQty) != 0) {
                throw new com.lrj.wms.runtime.messaging.MessageRejectedException("RESULT_QUANTITY_MISMATCH");
            }
            if (command.get("safe_close_id") != null || !commandId.equals(String.valueOf(effect.get("active_command_id")))) {
                throw new com.lrj.wms.runtime.messaging.MessageRejectedException("STALE_EXECUTION_ATTEMPT");
            }
        }
        if (java.util.Set.of("APPLIED", "REJECTED", "CANCELLED").contains(String.valueOf(command.get("state")))) {
            if (!resultState.equals(command.get("state")) || !java.util.Objects.equals(postingId, command.get("posting_id"))) {
                throw new com.lrj.wms.runtime.messaging.MessageRejectedException("CONFLICTING_COMMAND_RESULT");
            }
            Map<String, Object> replay = view(command, String.valueOf(command.get("business_effect_key")));
            replay.put("consumed", false);
            return replay;
        }
        var resultPayload = new java.util.LinkedHashMap<String, Object>();
        resultPayload.put("state", resultState); resultPayload.put("postingId", postingId);
        int inserted = mapper.insertInbox(eventId, enterpriseId, warehouseId, commandId, "InventoryCommandResult",
                com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(resultPayload), now);
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

    /** 用例在剩余额度检查之前恢复旧结果；只读探测不创建无效命令，首次提交仍由效果锁和唯一约束裁决。 */
    public Map<String, Object> replayIfPresent(String action, String factType, String enterpriseId, String warehouseId,
            String commandId, String parentId, String partId, String lineId, BigDecimal qty) {
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, action, factType, parentId, partId, lineId);
        Map<String, Object> command = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (command == null && effectId != null) command = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
        if (command == null) return null;
        if (effectId == null) throw new com.lrj.wms.runtime.command.CommandConflictException();
        return submission(command, effectId, action, qty, true);
    }

    /** 将重放结果显式返回给用例，实物累计只能在首次提交时执行。 */
    private static Map<String, Object> submission(Map<String, Object> command, String effectId, String action,
            BigDecimal qty, boolean replayed) {
        com.lrj.wms.runtime.command.CommandReplay.requireSamePayload(command, action, effectId, qty);
        Map<String, Object> result = view(command, effectId);
        result.put("replayed", replayed);
        return result;
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
