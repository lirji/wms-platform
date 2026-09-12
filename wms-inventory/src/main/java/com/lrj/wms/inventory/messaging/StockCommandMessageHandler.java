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
        if (!"wms-inbound".equals(message.sourceService()) || !"StockCommandRequested".equals(message.eventType())) {
            throw new MessageRejectedException("UNSUPPORTED_COMMAND_SOURCE");
        }
        JsonNode payload = message.payload();
        String action = required(payload, "action");
        if (!"RECEIVE".equals(action)) throw new MessageRejectedException("UNSUPPORTED_COMMAND_ACTION");
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
        if (flag(sku.get("serial_enabled"))) throw new MessageRejectedException("SERIAL_OBSERVATION_REQUIRED");
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
        var command = new StockCommandService(session, clock).applyReceive(enterprise, warehouse, message.sourceService(), commandId,
                required(payload, "factParentId"), required(payload, "factPartId"), required(payload, "factLineId"),
                context.documentId(), required(payload, "actorId"), required(payload, "sourceExecutionId"), bucket, qty,
                payload.hasNonNull("previousCommandId") ? required(payload, "previousCommandId") : null);
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
