package com.lrj.wms.outbound.messaging;

import com.lrj.wms.outbound.order.OutboundAuthorizationMapper;
import com.lrj.wms.outbound.order.OutboundAuthorizationService;
import com.lrj.wms.outbound.order.OutboundOrderService;
import com.lrj.wms.runtime.messaging.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 可信履约快照驱动本库建单/证据/授权，最后Inbox完成失败时全部回滚。 */
public final class AllocationAuthorizationHandler implements RuntimeInbox.Handler {
    private final Clock clock;
    public AllocationAuthorizationHandler(Clock clock) {this.clock=clock;}

    /** 授权可先于建单事件到达，完整原快照可幂等补建；普通建单事件永不单独授予执行权。 */
    @Override public void apply(SqlSession session,RuntimeMessage message) {
        try { applyVerified(session,message); }
        catch (com.lrj.wms.outbound.order.OutboundException conflict) {
            if (java.util.Set.of("IDEMPOTENCY_PAYLOAD_MISMATCH","INVALID_LINE","INVALID_OWNER","ATTEMPT_MISMATCH",
                    "EVIDENCE_MISMATCH","AUTH_CONFLICT").contains(conflict.code())) throw new MessageRejectedException(conflict.code());
            throw conflict;
        }
    }
    private void applyVerified(SqlSession session,RuntimeMessage message) {
        var value=AllocationAuthorizationMessage.from(message);
        var lines=value.lines().stream().map(line -> {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("orderLineId",line.orderLineId());row.put("skuId",line.skuId());
            row.put("qty",line.qty());row.put("baseUnit",line.baseUnit());return row;
        }).toList();
        var order=new OutboundOrderService(session,clock).createFromAllocation(value.enterpriseId(),value.warehouseId(),
                value.allocationId(),value.attemptId(),value.ownerId(),null,lines);
        String body=RuntimeMessage.JSON.writeValueAsString(value);
        var mapper=session.getMapper(OutboundAuthorizationMapper.class);
        mapper.insertEvidence(UUID.randomUUID().toString(),value.enterpriseId(),value.warehouseId(),value.attemptId(),
                value.xid(),value.tcTerminalEvidenceRef(),value.participantSetHash(),body,Timestamp.from(clock.instant()));
        var evidence=mapper.lockEvidence(value.enterpriseId(),value.warehouseId(),value.attemptId());
        if (evidence==null || evidence.get("barrier_payload")==null)
            throw new MessageRejectedException("LEGACY_AUTHORIZATION_CONTEXT_MISSING");
        if (!value.xid().equals(evidence.get("xid")) || !"Committed".equals(evidence.get("tc_observed_status"))
                || !value.tcTerminalEvidenceRef().equals(evidence.get("tc_terminal_evidence_ref"))
                || !value.participantSetHash().equals(evidence.get("participant_set_hash"))
                || !RuntimeMessage.contentHash(body).equals(RuntimeMessage.contentHash(String.valueOf(evidence.get("barrier_payload")))))
            throw new MessageRejectedException("AUTHORIZATION_EVIDENCE_CONFLICT");
        if ("ExecutionAuthorizationRequested".equals(message.eventType())) {
            new OutboundAuthorizationService(session,clock).authorize(value.enterpriseId(),value.warehouseId(),
                    String.valueOf(order.get("id")),value.authorizationId(),"wms-fulfillment",value.attemptId(),
                    value.authorizationId(),value.xid(),value.tcTerminalEvidenceRef(),value.participantSetHash());
        }
    }
}
