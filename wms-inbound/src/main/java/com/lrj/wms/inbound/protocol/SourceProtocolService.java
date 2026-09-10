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

/** 入库 T1/T3。T1 提交后再触发库存 T2，不跨库持事务。 */
public final class SourceProtocolService {
    public static final String SOURCE = "wms-inbound";
    public static final String ACTION_RECEIVE = "RECEIVE";

    private final SqlSession session;
    private final Clock clock;

    public SourceProtocolService(SqlSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /** T1：保存效果、命令、实物与 Outbox。 */
    public Map<String, Object> submitReceive(String enterpriseId, String warehouseId, String commandId, String parentId,
            String partId, String lineId, String actorId, BigDecimal qty) {
        Timestamp now = Timestamp.from(clock.instant());
        SourceMapper mapper = session.getMapper(SourceMapper.class);
        String digest = sha256(ACTION_RECEIVE + '\u001f' + commandId + '\u001f' + qty.toPlainString());
        String effectCandidate = UUID.randomUUID().toString();
        mapper.insertEffect(effectCandidate, enterpriseId, warehouseId, SOURCE, ACTION_RECEIVE, "RECEIPT_PART", parentId,
                partId, lineId, commandId, "PENDING", now);
        String effectId = mapper.findEffectId(enterpriseId, warehouseId, SOURCE, ACTION_RECEIVE, "RECEIPT_PART", parentId,
                partId, lineId);
        Map<String, Object> existing = mapper.getCommand(enterpriseId, warehouseId, commandId);
        if (existing != null) {
            return view(existing, effectId);
        }
        String executionId = UUID.randomUUID().toString();
        String payload = "{\"qty\":\"" + qty.toPlainString() + "\",\"commandId\":\"" + commandId + "\"}";
        mapper.insertCommand(enterpriseId, warehouseId, commandId, commandId, executionId, effectId, ACTION_RECEIVE, digest,
                payload, "PENDING", now);
        mapper.insertExecution(executionId, enterpriseId, warehouseId, commandId, ACTION_RECEIVE, qty, actorId, now);
        mapper.insertOutbox(UUID.randomUUID().toString(), enterpriseId, warehouseId, commandId, "StockCommandRequested",
                payload, now);
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
            mapper.updateEffectApplied(enterpriseId, warehouseId, mapper.findEffectByCommand(enterpriseId, warehouseId, commandId),
                    commandId, resultState, now);
        }
        return view(mapper.getCommand(enterpriseId, warehouseId, commandId),
                mapper.findEffectByCommand(enterpriseId, warehouseId, commandId));
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

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("缺少SHA-256", ex);
        }
    }
}
