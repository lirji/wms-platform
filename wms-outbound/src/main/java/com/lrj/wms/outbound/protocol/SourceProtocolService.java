package com.lrj.wms.outbound.protocol;

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

/** 出库 T1/T3。先锁 source_effect；同事实换键复用原命令。 */
public final class SourceProtocolService {
    public static final String SOURCE = "wms-outbound";
    public static final String ACTION_SHIP = "SHIP";
    public static final String ACTION_PICK = "PICK";
    public static final String ACTION_CANCEL = "CANCEL";

    private final SqlSession session;
    private final Clock clock;

    public SourceProtocolService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** T1：保存效果、命令、实物与 Outbox。 */
    public Map<String, Object> submitShip(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String digest = sha256(ACTION_SHIP + '\u001f' + commandId + '\u001f' + qty.toPlainString());
        String effectCandidate = UUID.randomUUID().toString();
        mapper.insertEffect(effectCandidate, enterpriseId, warehouseId, SOURCE, ACTION_SHIP, "SHIPMENT_PART", parentId,
                partId, lineId, commandId, "REGISTERED", now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, ACTION_SHIP, "SHIPMENT_PART", parentId,
                partId, lineId);
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> existing = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            return submission(existing, effectId, ACTION_SHIP, qty, true);
        }
        if (effect.get("applied_command_id") != null) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, String.valueOf(effect.get("applied_command_id"))), effectId, ACTION_SHIP, qty, true);
        }
        Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
        if (latest != null) {
            return submission(latest, effectId, ACTION_SHIP, qty, true);
        }
        if (effect.get("active_command_id") != null) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, String.valueOf(effect.get("active_command_id"))), effectId, ACTION_SHIP, qty, true);
        }
        String executionId = UUID.randomUUID().toString();
        String payload = com.lrj.wms.runtime.command.CommandReplay.payload(commandId, qty);
        if (mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_SHIP, 1L, null,
                digest, payload, "PENDING", now) != 1) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_SHIP, qty, true);
        }
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_SHIP, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1L, "OPEN", now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, ACTION_SHIP, qty, false);
    }

    /** T1：拣货子动作命令。 */
    public Map<String, Object> submitPick(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty) {
        return submitAction(ACTION_PICK, "SUB_ACTION", enterpriseId, warehouseId, commandId, parentId, partId, lineId,
                actorId, qty, null);
    }

    public Map<String, Object> submitPick(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty, String previousCommandId) {
        return submitAction(ACTION_PICK, "SUB_ACTION", enterpriseId, warehouseId, commandId, parentId, partId, lineId,
                actorId, qty, previousCommandId);
    }

    /** T1：发运前取消未执行量。 */
    public Map<String, Object> submitCancel(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty) {
        return submitAction(ACTION_CANCEL, "SUB_ACTION", enterpriseId, warehouseId, commandId, parentId, partId, lineId,
                actorId, qty, null);
    }

    private Map<String, Object> submitAction(String action, String factType, String enterpriseId, String warehouseId,
            String commandId, String parentId, String partId, String lineId, String actorId, BigDecimal qty,
            String previousCommandId) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String digest = sha256(action + '\u001f' + commandId + '\u001f' + qty.toPlainString());
        String effectCandidate = UUID.randomUUID().toString();
        mapper.insertEffect(effectCandidate, enterpriseId, warehouseId, SOURCE, action, factType, parentId, partId,
                lineId, commandId, "REGISTERED", now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, action, factType, parentId, partId,
                lineId);
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, effectId);
        Map<String, Object> existing = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            return submission(existing, effectId, action, qty, true);
        }
        if (previousCommandId == null || previousCommandId.isBlank()) {
            Map<String, Object> latest = mapper.findLatestCommand(enterpriseId, warehouseId, effectId);
            if (latest != null) {
                return submission(latest, effectId, action, qty, true);
            }
        } else {
            if (!"SAFE_CLOSED".equals(String.valueOf(effect.get("state")))) {
                throw new IllegalStateException("STALE_EXECUTION_ATTEMPT");
            }
            if (!previousCommandId.equals(String.valueOf(effect.get("active_command_id")))) {
                throw new IllegalStateException("STALE_EXECUTION_ATTEMPT");
            }
            com.lrj.wms.runtime.command.CommandReplay.requireSamePayload(
                    mapper.getCommand(enterpriseId, warehouseId, previousCommandId), action, effectId, qty);
            long attemptNo = ((Number) effect.get("attempt_no")).longValue() + 1;
            if (mapper.casNextAttempt(enterpriseId, warehouseId, effectId, attemptNo, commandId, "OPEN",
                    "SAFE_CLOSED", ((Number) effect.get("version")).longValue(), now) != 1) {
                throw new IllegalStateException("VERSION_CONFLICT");
            }
            String executionId = UUID.randomUUID().toString();
            String payload = com.lrj.wms.runtime.command.CommandReplay.payload(commandId, qty);
            if (mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, action, attemptNo,
                    previousCommandId, digest, payload, "PENDING", now) != 1) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, action, qty, true);
        }
            mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, action, qty, actorId, now);
            mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                    payload, now);
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, action, qty, false);
        }
        String executionId = UUID.randomUUID().toString();
        String payload = com.lrj.wms.runtime.command.CommandReplay.payload(commandId, qty);
        if (mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, action, 1L, null,
                digest, payload, "PENDING", now) != 1) {
            return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, action, qty, true);
        }
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, action, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
        if (mapper.casBindActive(enterpriseId, warehouseId, effectId, commandId, 1L, "OPEN", now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        return submission(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId, action, qty, false);
    }

    /** 未过账命令安全关闭，之后才允许同一效果的下一尝试。 */
    public Map<String, Object> safeClose(String enterpriseId, String warehouseId, String commandId) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        Map<String, Object> command = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (command == null) {
            throw new IllegalStateException("RESOURCE_NOT_FOUND");
        }
        String effectId = String.valueOf(command.get("business_effect_key"));
        Map<String, Object> effect = mapper.lockEffect(enterpriseId, warehouseId, effectId);
        if (effect.get("applied_command_id") != null) {
            throw new IllegalStateException("EFFECT_ALREADY_APPLIED");
        }
        String state = String.valueOf(effect.get("state"));
        if ("STARTED".equals(state) || "UNKNOWN".equals(state)) {
            throw new IllegalStateException("STALE_EXECUTION_ATTEMPT");
        }
        String closeId = UUID.randomUUID().toString();
        if (mapper.markSafeClose(enterpriseId, warehouseId, commandId, closeId,
                "{\"commandId\":\"" + commandId + "\"}", now) != 1) {
            throw new IllegalStateException("RESOURCE_NOT_FOUND");
        }
        if (mapper.casSafeClose(enterpriseId, warehouseId, effectId, commandId, now) != 1) {
            throw new IllegalStateException("VERSION_CONFLICT");
        }
        Map<String, Object> body = view(mapper.getCommand(enterpriseId, warehouseId, commandId), effectId);
        body.put("safeCloseRef", closeId);
        return body;
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
        result.put("qty", com.lrj.wms.runtime.command.CommandReplay.quantity(command));
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

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
    }
}
