package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.messaging.MessageRejectedException;
import com.lrj.wms.runtime.messaging.RuntimeInbox;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.ibatis.session.SqlSession;
import tools.jackson.databind.JsonNode;

/** 仓确认只更新已绑定原分支；全部参与仓和TC证据齐备前不生成任何出库授权。 */
public final class ReservationConfirmationHandler implements RuntimeInbox.Handler {
    private final Clock clock;
    public ReservationConfirmationHandler(Clock clock) { this.clock = clock; }

    /** Inbox处理完成与本仓确认、可能满足的屏障Outbox处于同一本地事务。 */
    @Override
    public void apply(SqlSession session, RuntimeMessage message) {
        if (!"wms-inventory".equals(message.sourceService()) || !"ReservationConfirmed".equals(message.eventType())) {
            throw new MessageRejectedException("UNSUPPORTED_RESERVATION_EVENT");
        }
        var body = message.payload();
        if (!body.path("confirmationSchemaVersion").isIntegralNumber()
                || !body.path("confirmationSchemaVersion").canConvertToInt()
                || body.path("confirmationSchemaVersion").asInt() != 1
                || !"CONFIRMED".equals(text(body, "state", 32)) || message.aggregateVersion() < 1) {
            throw new MessageRejectedException("INVALID_RESERVATION_CONFIRMATION");
        }
        String attemptId = text(body, "attemptId", 64), reservationId = text(body, "reservationId", 64);
        String allocationId = text(body, "allocationId", 64), xid = text(body, "xid", 128);
        String action = text(body, "actionName", 64);
        long branchId = number(body, "branchId", 1), routeEpoch = number(body, "routeEpoch", 0);
        if (!reservationId.equals(message.aggregateId())) throw new MessageRejectedException("RESERVATION_IDENTITY_MISMATCH");
        var mapper = session.getMapper(FulfillmentMapper.class);
        var attempt = mapper.lockAttempt(message.enterpriseId(), attemptId);
        if (attempt == null || attempt.get("xid") == null) {
            throw new FulfillmentException("ATTEMPT_BINDING_PENDING", "确认消息等待原attempt绑定，不从消息创建新attempt");
        }
        if (!xid.equals(attempt.get("xid")) || !Set.of("TCC_TRYING", "TCC_COMPLETING", "ALLOCATED").contains(attempt.get("state"))) {
            throw new MessageRejectedException("ATTEMPT_IDENTITY_MISMATCH");
        }
        var participants = mapper.lockParticipants(message.enterpriseId(), attemptId);
        Map<String, Object> participant = participants.stream().filter(row -> message.warehouseId().equals(row.get("warehouse_id")))
                .findFirst().orElseThrow(() -> new MessageRejectedException("UNPLANNED_PARTICIPANT"));
        if (participant.get("xid") == null || participant.get("reservation_id") == null) {
            throw new FulfillmentException("PARTICIPANT_BINDING_PENDING", "原Try分支回执尚未完成持久化，等待原启动恢复");
        }
        if (!xid.equals(participant.get("xid")) || !reservationId.equals(participant.get("reservation_id"))
                || !action.equals(participant.get("action_name"))
                || branchId != ((Number) participant.get("branch_id")).longValue()
                || routeEpoch != ((Number) participant.get("route_epoch")).longValue()) {
            throw new MessageRejectedException("RESERVATION_BRANCH_MISMATCH");
        }
        Object previousAllocation = participant.get("confirmed_allocation_id");
        if (previousAllocation != null && !allocationId.equals(previousAllocation)) {
            throw new MessageRejectedException("RESERVATION_ALLOCATION_MISMATCH");
        }
        if (participants.stream().anyMatch(row -> row.get("confirmed_allocation_id") != null
                && !allocationId.equals(row.get("confirmed_allocation_id")))) {
            throw new MessageRejectedException("ALLOCATION_PARTICIPANTS_MISMATCH");
        }
        var service = new FulfillmentService(session, clock);
        try {
            service.observeParticipant(message.enterpriseId(), attemptId, message.warehouseId(), "CONFIRMED", message.aggregateVersion());
        } catch (FulfillmentException conflict) { throw new MessageRejectedException(conflict.code()); }
        if (previousAllocation == null && mapper.bindConfirmedAllocation(message.enterpriseId(), attemptId,
                message.warehouseId(), allocationId, java.sql.Timestamp.from(clock.instant())) != 1) {
            throw new FulfillmentException("VERSION_CONFLICT", "原预占分配标识写入竞争");
        }
        // 旧attempt即使有手工状态文本，也不能绕过明确TC来源绑定。
        var binding = session.getMapper(AllocationRecoveryMapper.class).binding(message.enterpriseId(), attemptId);
        if (binding != null && xid.equals(binding.get("xid"))
                && Objects.equals(attempt.get("launch_epoch"), binding.get("launch_epoch"))) {
            try { service.markAllocated(message.enterpriseId(), attemptId); }
            catch (FulfillmentException pending) { if (!FulfillmentService.isRecoveryPending(pending)) throw pending; }
        }
    }

    private static String text(JsonNode body, String field, int max) {
        var value = body.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max) {
            throw new MessageRejectedException("INVALID_RESERVATION_CONFIRMATION");
        }
        return value.asString();
    }
    private static long number(JsonNode body, String field, long minimum) {
        var value = body.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) {
            throw new MessageRejectedException("INVALID_RESERVATION_CONFIRMATION");
        }
        return value.longValue();
    }
}
