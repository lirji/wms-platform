package com.lrj.wms.runtime.messaging;

import com.lrj.wms.contract.messaging.AllocationAuthorization;
import tools.jackson.databind.JsonNode;

/** 公开授权契约的严格JSON边界，拒绝数值截断或把其他TC来源证据变成可执行授权。 */
public final class AllocationAuthorizationMessage {
    private AllocationAuthorizationMessage() { }

    /** 新消息只接受完整V1快照；旧的最小建单事件必须保留隔离待处理。 */
    public static AllocationAuthorization parse(JsonNode body) {
        try {
            if (body == null || !body.isObject() || !body.path("authorizationSchemaVersion").isIntegralNumber()
                    || !body.path("authorizationSchemaVersion").canConvertToInt()
                    || body.path("authorizationSchemaVersion").intValue() != 1)
                throw new MessageRejectedException("AUTHORIZATION_SCHEMA_UNSUPPORTED");
            strings(body, "enterpriseId", "warehouseId", "fulfillmentId", "ownerId", "allocationId", "attemptId", "xid",
                    "authorizationId", "tcTerminalEvidenceRef", "participantSetHash");
            var proof = body.path("tcProof");
            strings(proof, "clusterId", "applicationId", "transactionGroup", "terminalEvidence");
            if (!body.path("participants").isArray() || !body.path("lines").isArray()) throw new IllegalArgumentException();
            for (var participant : body.path("participants")) {
                strings(participant, "warehouseId", "allocationId", "reservationId", "actionName");
                for (String number : new String[]{"branchId", "routeEpoch", "confirmedVersion"}) {
                    if (!participant.path(number).isIntegralNumber() || !participant.path(number).canConvertToLong())
                        throw new IllegalArgumentException();
                }
            }
            for (var line : body.path("lines")) {
                strings(line, "orderLineId", "skuId", "baseUnit");
                if (!line.path("qty").isNumber() && !line.path("qty").isString()) throw new IllegalArgumentException();
            }
            var value = RuntimeMessage.JSON.treeToValue(body, AllocationAuthorization.class);
            var evidence = RuntimeMessage.JSON.readTree(value.tcProof().terminalEvidence());
            strings(evidence, "xid", "clusterId", "applicationId", "transactionGroup");
            if (!evidence.path("status").isIntegralNumber() || !evidence.path("status").canConvertToInt()
                    || evidence.path("status").intValue() != 9
                    || !value.xid().equals(evidence.path("xid").asString())
                    || !value.tcProof().clusterId().equals(evidence.path("clusterId").asString())
                    || !value.tcProof().applicationId().equals(evidence.path("applicationId").asString())
                    || !value.tcProof().transactionGroup().equals(evidence.path("transactionGroup").asString()))
                throw new MessageRejectedException("AUTHORIZATION_TC_EVIDENCE_MISMATCH");
            return value;
        } catch (MessageRejectedException rejected) { throw rejected; }
        catch (RuntimeException malformed) { throw new MessageRejectedException("INVALID_ALLOCATION_AUTHORIZATION"); }
    }

    /** 信封范围不能覆盖正文的原业务范围，聚合版本固定为这次屏障的V1。 */
    public static AllocationAuthorization from(RuntimeMessage message) {
        var value = parse(message.payload());
        if (!"wms-fulfillment".equals(message.sourceService())
                || !java.util.Set.of("OutboundOrderRequested", "ExecutionAuthorizationRequested").contains(message.eventType())
                || !value.enterpriseId().equals(message.enterpriseId()) || !value.warehouseId().equals(message.warehouseId())
                || !value.attemptId().equals(message.aggregateId()) || message.aggregateVersion() != 1)
            throw new MessageRejectedException("AUTHORIZATION_ENVELOPE_MISMATCH");
        return value;
    }

    private static void strings(JsonNode body, String... names) {
        if (!body.isObject()) throw new IllegalArgumentException();
        for (String name : names) if (!body.path(name).isString()) throw new IllegalArgumentException();
    }
}
