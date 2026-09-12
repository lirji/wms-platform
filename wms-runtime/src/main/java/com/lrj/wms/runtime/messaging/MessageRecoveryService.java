package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.MessageRecoveryMapper;
import com.lrj.wms.runtime.web.CursorPage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.SqlSessionFactory;

/** 受权恢复仅重新排队原事件，不修改业务事实，也不宣称业务已完成。 */
public final class MessageRecoveryService {
    private final SqlSessionFactory sessions;
    private final MessageQueueMetrics.Queue outbox;
    private final RuntimeInbox inbox;
    private final Clock clock;

    public MessageRecoveryService(SqlSessionFactory sessions, MessageQueueMetrics.Queue outbox, RuntimeInbox inbox, Clock clock) {
        if (outbox == MessageQueueMetrics.Queue.INBOX) throw new IllegalArgumentException("缺少Outbox类别");
        this.sessions = sessions; this.outbox = outbox; this.inbox = inbox; this.clock = clock;
    }

    /** 游标绑定身份与筛选，SQL始终带企业/仓范围。 */
    public Map<String, Object> list(String enterprise, String warehouse, String queue, String status, Integer limit, String cursor) {
        String kind = queueKind(queue);
        if (!Set.of("PENDING", "CLAIMED", "ISOLATED").contains(status)) throw new MessageRecoveryException("INVALID_MESSAGE_STATE");
        var page = CursorPage.parse(limit, cursor, CursorPage.scope("messages", enterprise, warehouse, kind, status));
        try (var session = sessions.openSession()) {
            return page.result(session.getMapper(MessageRecoveryMapper.class).list(enterprise, warehouse, kind, status, page), false);
        }
    }

    /** JWT操作者和幂等键由边界提供；重放同请求返回原审计，不二次恢复预算。 */
    public Map<String, Object> retry(String enterprise, String warehouse, String queue, String messageId,
            String commandId, long epoch, String reason, String actor) {
        String kind = queueKind(queue);
        if (epoch < 0 || !validId(enterprise) || !validId(warehouse) || !validId(messageId) || !validId(commandId)
                || !validId(actor) || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new MessageRecoveryException("INVALID_RECOVERY_REQUEST");
        }
        String requestHash = RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(
                java.util.List.of(enterprise, warehouse, kind, messageId, commandId, epoch, reason, actor)));
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(MessageRecoveryMapper.class);
            var message = mapper.lockMessage(enterprise, warehouse, kind, messageId);
            if (message == null) throw new MessageRecoveryException("MESSAGE_NOT_FOUND");
            var previous = mapper.audit(enterprise, warehouse, commandId);
            if (previous != null) {
                if (!requestHash.equals(previous.get("request_hash"))) throw new MessageRecoveryException("RECOVERY_KEY_CONFLICT");
                session.commit();
                return result(String.valueOf(previous.get("id")), messageId, true);
            }
            if (!"ISOLATED".equals(message.get("status")) || ((Number) message.get("claim_epoch")).longValue() != epoch) {
                throw new MessageRecoveryException("MESSAGE_STATE_CONFLICT");
            }
            if ("INBOX".equals(kind)) {
                try { inbox.validateTrusted(message); }
                catch (MessageRejectedException invalid) { throw new MessageRecoveryException("MESSAGE_NOT_REPLAYABLE"); }
            } else if (outbox == MessageQueueMetrics.Queue.SOURCE_OUTBOX
                    && Set.of("LEGACY_COMMAND_CONTEXT_MISSING", "POSTING_CONTEXT_MISMATCH", "SOURCE_COMMAND_IDENTITY_MISMATCH",
                            "INVALID_COMMAND_CONTEXT", "STALE_EXECUTION_ATTEMPT").contains(String.valueOf(message.get("error_code")))) {
                throw new MessageRecoveryException("MESSAGE_NOT_REPLAYABLE");
            }
            if (outbox == MessageQueueMetrics.Queue.FULFILLMENT_OUTBOX && !"INBOX".equals(kind)
                    && !"AllocationCompleted".equals(message.get("event_type"))) {
                try {
                    var authorization=AllocationAuthorizationMessage.parse(RuntimeMessage.JSON.readTree(String.valueOf(message.get("payload"))));
                    if (!enterprise.equals(authorization.enterpriseId()) || !warehouse.equals(authorization.warehouseId())
                            || !authorization.attemptId().equals(message.get("attempt_id")))
                        throw new MessageRejectedException("AUTHORIZATION_ENVELOPE_MISMATCH");
                } catch (MessageRejectedException invalid) { throw new MessageRecoveryException("MESSAGE_NOT_REPLAYABLE"); }
            }
            String id = UUID.randomUUID().toString();
            var audit = new LinkedHashMap<String, Object>();
            audit.put("id", id); audit.put("enterpriseId", enterprise); audit.put("warehouseId", warehouse);
            audit.put("commandId", commandId); audit.put("queue", kind); audit.put("messageId", messageId);
            audit.put("requestHash", requestHash); audit.put("payloadHash", RuntimeMessage.hash(String.valueOf(message.get("payload"))));
            audit.put("epoch", epoch); audit.put("actor", actor); audit.put("reason", reason); audit.put("now", Timestamp.from(clock.instant()));
            if (mapper.retry(enterprise, warehouse, kind, messageId, epoch, Timestamp.from(clock.instant())) != 1
                    || mapper.insertAudit(audit) != 1) throw new MessageRecoveryException("MESSAGE_STATE_CONFLICT");
            session.commit();
            return result(id, messageId, false);
        }
    }

    private String queueKind(String queue) {
        return switch (queue) {
            case "INBOX" -> "INBOX";
            case "OUTBOX" -> outbox.name();
            default -> throw new MessageRecoveryException("INVALID_MESSAGE_QUEUE");
        };
    }
    private static boolean validId(String value) { return value != null && !value.isBlank() && value.length() <= 64; }
    private static Map<String, Object> result(String id, String messageId, boolean replayed) {
        return Map.of("recoveryId", id, "messageId", messageId, "state", "RETRY_ACCEPTED", "replayed", replayed);
    }
}
