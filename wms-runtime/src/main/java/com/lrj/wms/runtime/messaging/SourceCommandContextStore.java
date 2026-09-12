package com.lrj.wms.runtime.messaging;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.runtime.command.CommandConflictException;
import com.lrj.wms.runtime.messaging.persistence.SourceContextMapper;
import com.lrj.wms.runtime.observability.RequestCorrelationFilter;
import org.apache.ibatis.session.SqlSession;
import tools.jackson.databind.node.ObjectNode;

/** 完整过账上下文与来源命令/Outbox在同一T1提交，不能为历史重放猜测原始库存维度。 */
public final class SourceCommandContextStore {
    private final SqlSession session;
    public SourceCommandContextStore(SqlSession session) { this.session = session; }

    /** 首次绑定保留请求关联；重试只比较业务维度，不改第一次的actor/执行时间/requestId。 */
    public void bind(String enterpriseId, String warehouseId, String commandId, StockPostingContext context, boolean replayed) {
        bind(enterpriseId, warehouseId, commandId, context, null, replayed);
    }

    /** 上架等衍生动作额外固定原收货批次，不能仅因两个批次落在同桶就允许互换。 */
    public void bind(String enterpriseId, String warehouseId, String commandId, StockPostingContext context,
            String receiptCommandId, boolean replayed) {
        bindContext(enterpriseId, warehouseId, commandId, context, receiptCommandId, null, null, replayed);
    }

    /** 首次来源T1同时绑定完整收货身份；重放不能遗漏、替换或追加原清单。 */
    public void bindReceipt(String enterpriseId, String warehouseId, String commandId, StockPostingContext context,
            com.lrj.wms.contract.messaging.SerialReceiptObservation observation, boolean replayed) {
        bindContext(enterpriseId, warehouseId, commandId, context, null, null, observation, replayed);
    }

    /** 出库同时冻结权威预占使用的原订单行，不能把来源库的内部行ID当作预占订单行。 */
    public void bindOutbound(String enterpriseId, String warehouseId, String commandId, StockPostingContext context,
            String reservationOrderLineId, boolean replayed) {
        if (reservationOrderLineId == null || reservationOrderLineId.isBlank() || reservationOrderLineId.length() > 64)
            throw new CommandConflictException();
        bindContext(enterpriseId, warehouseId, commandId, context, null, reservationOrderLineId, null, replayed);
    }

    private void bindContext(String enterpriseId, String warehouseId, String commandId, StockPostingContext context,
            String receiptCommandId, String reservationOrderLineId,
            com.lrj.wms.contract.messaging.SerialReceiptObservation observation, boolean replayed) {
        if (receiptCommandId != null && (receiptCommandId.isBlank() || receiptCommandId.length() > 64)) throw new CommandConflictException();
        var mapper = session.getMapper(SourceContextMapper.class);
        var command = mapper.lockCommand(enterpriseId, warehouseId, commandId);
        if (command == null) throw new IllegalStateException("来源命令不存在");
        String action = String.valueOf(command.get("action"));
        context.requireForAction(action);
        if (reservationOrderLineId != null && !java.util.Set.of("PICK", "SHIP", "CANCEL").contains(action)) throw new CommandConflictException();
        var payload = RuntimeMessage.JSON.readTree(String.valueOf(command.get("payload_json")));
        if (!(payload instanceof ObjectNode object)) throw new IllegalStateException("来源命令正文无效");
        if (observation != null) {
            if (!"RECEIVE".equals(action)) throw new CommandConflictException();
            observation.requireQuantity(new java.math.BigDecimal(object.path("qty").asString()));
        }
        var supplied = RuntimeMessage.JSON.valueToTree(context);
        if (object.has("postingContext")) {
            if (!object.path("postingContext").equals(supplied)
                    || !java.util.Objects.equals(object.get("serialObservation"), observation == null ? null : RuntimeMessage.JSON.valueToTree(observation))
                    || receiptCommandId != null && !receiptCommandId.equals(object.path("receiptCommandId").asString())
                    || reservationOrderLineId != null && (!reservationOrderLineId.equals(object.path("reservationOrderLineId").asString())
                        || !object.path("outboundSchemaVersion").isIntegralNumber()
                        || !object.path("outboundSchemaVersion").canConvertToInt()
                        || object.path("outboundSchemaVersion").intValue() != 1)) throw new CommandConflictException();
            return;
        }
        if (replayed) throw new MissingCommandContextException();
        if (reservationOrderLineId != null) {
            object.put("reservationOrderLineId", reservationOrderLineId); object.put("outboundSchemaVersion", 1);
        }
        if (receiptCommandId != null) object.put("receiptCommandId", receiptCommandId);
        if (observation != null) object.set("serialObservation", RuntimeMessage.JSON.valueToTree(observation));
        object.put("schemaVersion", 1);
        object.set("postingContext", supplied);
        object.put("postingContextDigest", RuntimeMessage.contentHash(supplied.toString()));
        object.put("requestId", RequestCorrelationFilter.currentId());
        String body = object.toString();
        if (mapper.bindCommand(enterpriseId, warehouseId, commandId, body) != 1
                || mapper.bindOutbox(enterpriseId, warehouseId, commandId, body) != 1) {
            throw new IllegalStateException("来源命令与待发布事件必须唯一且同时绑定");
        }
    }
}
