package com.lrj.wms.inventory.messaging;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.inventory.inventory.StockCommandService;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.*;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataHttpMapper;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import tools.jackson.databind.JsonNode;

/** 在Inbox同一库存本地事务内校验权威维度、过账和写回执Outbox，禁止回调来源服务HTTP。 */
public final class StockCommandMessageHandler implements RuntimeInbox.Handler {
    private final Clock clock;
    public StockCommandMessageHandler(Clock clock) { this.clock = clock; }

    @Override public void apply(SqlSession session, RuntimeMessage message) {
        boolean outbound = "wms-outbound".equals(message.sourceService());
        if ((!outbound && !"wms-inbound".equals(message.sourceService())) || !"StockCommandRequested".equals(message.eventType())) {
            throw new MessageRejectedException("UNSUPPORTED_COMMAND_SOURCE");
        }
        JsonNode payload = message.payload();
        String action = required(payload, "action");
        if (!(outbound ? Set.of("PICK", "SHIP", "CANCEL") : Set.of("RECEIVE", "QUALITY", "PUTAWAY")).contains(action))
            throw new MessageRejectedException("UNSUPPORTED_COMMAND_ACTION");
        // 序列出库使用独立V2：旧V1消费者会明确拒绝，不能在普通SKU上静默忽略新身份字段。
        if (outbound && (!payload.path("outboundSchemaVersion").isIntegralNumber()
                || !payload.path("outboundSchemaVersion").canConvertToInt() || payload.path("outboundSchemaVersion").intValue() != (payload.hasNonNull("serialExecution")?2:1)))
            throw new MessageRejectedException("UNSUPPORTED_OUTBOUND_SCHEMA");
        StockPostingContext context;
        BigDecimal rawQty;
        try {
            context = RuntimeMessage.JSON.treeToValue(payload.path("postingContext"), StockPostingContext.class);
            context.requireForAction(action);
            if (!payload.path("qty").isNumber() && !payload.path("qty").isString()) throw new IllegalArgumentException();
            rawQty = new BigDecimal(payload.path("qty").asString());
            if (rawQty.signum() <= 0) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new MessageRejectedException("INVALID_COMMAND_CONTEXT"); }
        var masterdata = session.getMapper(MasterdataHttpMapper.class);
        String enterprise = message.enterpriseId(), warehouse = message.warehouseId();
        var sku = masterdata.getSku(enterprise, context.skuId());
        var wh = masterdata.getWarehouse(enterprise, warehouse);
        var location = masterdata.getLocation(enterprise, warehouse, context.sourceLocationId());
        if (!active(sku) || !active(wh) || !active(location)) throw new MessageRejectedException("MASTERDATA_NOT_ACTIVE");
        if (!context.baseUnit().equals(sku.get("base_unit"))) throw new MessageRejectedException("BASE_UNIT_MISMATCH");
        boolean serialEnabled = flag(sku.get("serial_enabled"));
        com.lrj.wms.contract.messaging.SerialReceiptObservation serialObservation = null;
        if (serialEnabled && "RECEIVE".equals(action)) {
            try {
                var observation = payload.path("serialObservation");
                if (!observation.isObject() || observation.properties().stream().anyMatch(p -> !Set.of("schemaVersion", "serialIds").contains(p.getKey()))
                        || !observation.path("schemaVersion").isIntegralNumber() || !observation.path("schemaVersion").canConvertToInt()
                        || observation.path("schemaVersion").intValue() != 1 || !observation.path("serialIds").isArray()
                        || observation.path("serialIds").size() > 200) throw new IllegalArgumentException();
                for (var serial : observation.path("serialIds")) if (!serial.isString()) throw new IllegalArgumentException();
                serialObservation = RuntimeMessage.JSON.treeToValue(observation, com.lrj.wms.contract.messaging.SerialReceiptObservation.class);
                serialObservation.requireQuantity(rawQty);
            } catch (RuntimeException invalid) { throw new MessageRejectedException("SERIAL_OBSERVATION_REQUIRED"); }
        } else if (serialEnabled && !Set.of("CANCEL","QUALITY","PUTAWAY","PICK","SHIP").contains(action)) throw new MessageRejectedException("SERIAL_OBSERVATION_REQUIRED");
        else if (payload.hasNonNull("serialObservation")) throw new MessageRejectedException("SERIAL_POLICY_MISMATCH");
        if(payload.hasNonNull("serialQualityObservation") && (!serialEnabled || !"QUALITY".equals(action)))
            throw new MessageRejectedException("SERIAL_POLICY_MISMATCH");
        if(payload.hasNonNull("serialSelection") && (!serialEnabled || !"PUTAWAY".equals(action)))
            throw new MessageRejectedException("SERIAL_POLICY_MISMATCH");
        com.lrj.wms.contract.messaging.SerialExecutionSelection serialExecution=null;
        if(serialEnabled && Set.of("PICK","SHIP").contains(action)) {
            try {
                var raw=payload.path("serialExecution");
                if(!raw.isObject() || raw.properties().stream().anyMatch(p -> !Set.of("schemaVersion","identities").contains(p.getKey()))
                        || !raw.path("schemaVersion").isIntegralNumber() || !raw.path("schemaVersion").canConvertToInt() || raw.path("schemaVersion").intValue()!=1
                        || !raw.path("identities").isArray() || raw.path("identities").isEmpty() || raw.path("identities").size()>200) throw new IllegalArgumentException();
                for(var identity:raw.path("identities"))
                    if(!identity.isObject() || identity.properties().stream().anyMatch(p -> !Set.of("serialId","ownerEpoch").contains(p.getKey()))
                            || !identity.path("serialId").isString() || !identity.path("ownerEpoch").isIntegralNumber() || !identity.path("ownerEpoch").canConvertToLong()) throw new IllegalArgumentException();
                serialExecution=RuntimeMessage.JSON.treeToValue(raw,com.lrj.wms.contract.messaging.SerialExecutionSelection.class);serialExecution.requireQuantity(rawQty);
            } catch(RuntimeException invalid) {throw new MessageRejectedException("INVALID_SERIAL_EXECUTION");}
        } else if(payload.hasNonNull("serialExecution")) throw new MessageRejectedException("SERIAL_POLICY_MISMATCH");
        boolean hasLot = !"NO_LOT".equals(context.lotId());
        if (flag(sku.get("lot_enabled")) != hasLot) throw new MessageRejectedException("LOT_POLICY_MISMATCH");
        if (hasLot) {
            var lot = masterdata.getLot(enterprise, warehouse, context.lotId());
            if (lot == null || !context.ownerId().equals(lot.get("owner_id")) || !context.skuId().equals(lot.get("sku_id"))) {
                throw new MessageRejectedException("LOT_SCOPE_MISMATCH");
            }
        }
        Quantity qty;
        try { qty = Quantity.of(rawQty, ((Number) sku.get("quantity_scale")).intValue()); }
        catch (RuntimeException invalid) { throw new MessageRejectedException("QUANTITY_PRECISION_MISMATCH"); }
        String commandId = required(payload, "commandId");
        var bucket = StockBucketKey.of(enterprise, warehouse, context.ownerId(), context.sourceLocationId(), context.skuId(), context.lotId(), context.qualityCode());
        Map<String, Object> command;
        if (outbound) {
            if ("PICK".equals(action)) {
                var targetLocation = masterdata.getLocation(enterprise, warehouse, context.targetLocationId());
                if (!active(targetLocation)) throw new MessageRejectedException("INVALID_PICK_LOCATION");
            }
            command = new StockCommandService(session, clock).applyOutbound(enterprise, warehouse, commandId, action,
                    required(payload, "factParentId"), required(payload, "factPartId"), required(payload, "factLineId"),
                    required(payload, "actorId"), required(payload, "sourceExecutionId"), required(payload, "reservationOrderLineId"),
                    context, qty, payload.hasNonNull("previousCommandId") ? required(payload, "previousCommandId") : null,serialExecution);
        } else if ("QUALITY".equals(action)) {
            com.lrj.wms.contract.messaging.ReceiptQualityDecision decision;
            try {
                var rawDecision = payload.path("qualityDecision");
                if (!rawDecision.path("sourceVersion").isIntegralNumber() || !rawDecision.path("sourceVersion").canConvertToLong()
                        || !rawDecision.path("receiptCommandId").isString() || !rawDecision.path("inspectionId").isString()) throw new IllegalArgumentException();
                decision = RuntimeMessage.JSON.treeToValue(rawDecision, com.lrj.wms.contract.messaging.ReceiptQualityDecision.class);
                Quantity.of(decision.acceptedQty(), ((Number) sku.get("quantity_scale")).intValue());
                Quantity.of(decision.rejectedQty(), ((Number) sku.get("quantity_scale")).intValue());
                if (rawQty.compareTo(decision.inspectedQty()) != 0 || !decision.receiptCommandId().equals(required(payload, "factParentId"))
                        || !Long.toString(decision.sourceVersion()).equals(required(payload, "factPartId"))) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) { throw new MessageRejectedException("INVALID_QUALITY_DECISION"); }
            com.lrj.wms.contract.messaging.SerialQualityObservation qualityObservation=null;
            if(serialEnabled) {
                try {
                    var raw=payload.path("serialQualityObservation");
                    if(!raw.isObject() || raw.properties().stream().anyMatch(p->!Set.of("schemaVersion","acceptedSerials","rejectedSerials").contains(p.getKey()))
                            || !raw.path("schemaVersion").isIntegralNumber() || !raw.path("schemaVersion").canConvertToInt() || raw.path("schemaVersion").intValue()!=1)
                        throw new IllegalArgumentException();
                    for(String name:List.of("acceptedSerials","rejectedSerials")) {
                        if(!raw.path(name).isArray() || raw.path(name).size()>200) throw new IllegalArgumentException();
                        for(var serial:raw.path(name)) if(!serial.isString()) throw new IllegalArgumentException();
                    }
                    qualityObservation=RuntimeMessage.JSON.treeToValue(raw,com.lrj.wms.contract.messaging.SerialQualityObservation.class);
                    qualityObservation.requireDecision(decision);
                } catch(RuntimeException invalid) {throw new MessageRejectedException("INVALID_SERIAL_QUALITY");}
            }
            command = new StockCommandService(session, clock).applyQuality(enterprise, warehouse, commandId,
                    required(payload, "factLineId"), context.documentId(), required(payload, "actorId"),
                    required(payload, "sourceExecutionId"), bucket, decision, qualityObservation);
        } else if ("PUTAWAY".equals(action)) {
            com.lrj.wms.contract.messaging.SerialStockSelection selection=null;
            if(serialEnabled) {
                try {
                    var raw=payload.path("serialSelection");
                    if(!raw.isObject() || raw.properties().stream().anyMatch(p->!Set.of("schemaVersion","serialIds").contains(p.getKey()))
                            || !raw.path("schemaVersion").isIntegralNumber() || !raw.path("schemaVersion").canConvertToInt() || raw.path("schemaVersion").intValue()!=1
                            || !raw.path("serialIds").isArray() || raw.path("serialIds").size()>200) throw new IllegalArgumentException();
                    for(var serial:raw.path("serialIds")) if(!serial.isString()) throw new IllegalArgumentException();
                    selection=RuntimeMessage.JSON.treeToValue(raw,com.lrj.wms.contract.messaging.SerialStockSelection.class);selection.requireQuantity(rawQty);
                } catch(RuntimeException invalid) {throw new MessageRejectedException("INVALID_SERIAL_SELECTION");}
            }
            var targetLocation = masterdata.getLocation(enterprise, warehouse, context.targetLocationId());
            if (!active(targetLocation) || !"STORAGE".equals(targetLocation.get("location_type"))) throw new MessageRejectedException("INVALID_PUTAWAY_LOCATION");
            var target = StockBucketKey.of(enterprise, warehouse, context.ownerId(), context.targetLocationId(), context.skuId(), context.lotId(), "GOOD");
            command = new StockCommandService(session, clock).applyPutaway(enterprise, warehouse, commandId,
                    required(payload, "factParentId"), required(payload, "factPartId"), required(payload, "factLineId"),
                    context.documentId(), required(payload, "actorId"), required(payload, "sourceExecutionId"), required(payload, "receiptCommandId"), bucket, target, qty,selection);
        } else if (serialEnabled) {
            command = new com.lrj.wms.inventory.serial.SerialReceiptBatchService(session, clock).receive(enterprise, warehouse, commandId,
                    required(payload, "factParentId"), required(payload, "factPartId"), required(payload, "factLineId"),
                    required(payload, "actorId"), required(payload, "sourceExecutionId"), context, qty,
                    payload.hasNonNull("previousCommandId") ? required(payload, "previousCommandId") : null, serialObservation);
        } else {
            command = new StockCommandService(session, clock).applyReceive(enterprise, warehouse, message.sourceService(), commandId,
                required(payload, "factParentId"), required(payload, "factPartId"), required(payload, "factLineId"),
                context.documentId(), required(payload, "actorId"), required(payload, "sourceExecutionId"), bucket, qty,
                payload.hasNonNull("previousCommandId") ? required(payload, "previousCommandId") : null);
        }
        String effectiveCommand = String.valueOf(command.get("commandId"));
        if (!commandId.equals(effectiveCommand)) throw new MessageRejectedException("SOURCE_COMMAND_IDENTITY_MISMATCH");
        String state = String.valueOf(command.get("state"));
        var posting = session.getMapper(StockCommandMapper.class).postingByCommand(enterprise, warehouse, message.sourceService(), effectiveCommand);
        var result = new LinkedHashMap<String, Object>();
        result.put("recipientService", message.sourceService()); result.put("commandId", effectiveCommand);
        result.put("state", state); result.put("postingId", posting == null ? null : posting.get("id"));
        result.put("postedQty", posting == null ? BigDecimal.ZERO : posting.get("quantity"));
        result.put("requestId", message.requestId());
        if ("APPLIED".equals(state) && posting == null) throw new IllegalStateException("过账命令缺少凭证");
        if (!Set.of("APPLIED", "REJECTED", "CANCELLED", "UNKNOWN").contains(state)) throw new IllegalStateException("库存命令结果尚未确定");
        String eventId = RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(List.of(message.sourceService(), enterprise, warehouse, effectiveCommand, state)));
        session.getMapper(OutboxMapper.class).insertCommandResult(eventId, enterprise, warehouse, effectiveCommand,
                RuntimeMessage.JSON.writeValueAsString(result), Timestamp.from(clock.instant()));
    }
    private static boolean active(Map<String, Object> row) { return row != null && "ACTIVE".equals(row.get("state")); }
    private static boolean flag(Object value) { return value instanceof Boolean flag ? flag : value instanceof Number number && number.intValue() == 1; }
    private static String required(JsonNode payload, String field) {
        var value = payload.path(field);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > 64) throw new MessageRejectedException("INVALID_COMMAND_IDENTITY");
        return value.asString();
    }
}
