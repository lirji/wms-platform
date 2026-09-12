package com.lrj.wms.outbound.order;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceCommandContextStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;

/** HTTP出库T1：从本地授权订单/任务派生身份，将操作人选定的桶与原命令一起冻结。 */
public final class OutboundPostingService {
    private final SqlSession session;
    private final Clock clock;
    public OutboundPostingService(SqlSession session, Clock clock) { this.session = session; this.clock = clock; }

    /** 库位来自原任务，客户端只指定本次拣货实际批次；重试必须保持原上下文。 */
    public Map<String, Object> pick(String enterprise, String warehouse, String taskId, String commandId,
            String actor, BigDecimal qty, String partId, String lotId) {
        return pick(enterprise,warehouse,taskId,commandId,actor,qty,partId,lotId,null);
    }

    /** 所选SN及epoch与原任务同时受理，来源表不向库存库跨域写入。 */
    public Map<String,Object> pick(String enterprise,String warehouse,String taskId,String commandId,String actor,
            BigDecimal qty,String partId,String lotId,com.lrj.wms.contract.messaging.SerialExecutionSelection selection) {
        if(selection!=null) selection.requireQuantity(qty);
        var mapper = session.getMapper(OutboundOrderMapper.class);
        String orderId = mapper.taskOrderId(enterprise, warehouse, taskId);
        var order = require(orderId == null ? null : mapper.lockOrder(enterprise, warehouse, orderId), "UNKNOWN_ORDER");
        var task = require(mapper.lockTask(enterprise, warehouse, taskId), "UNKNOWN_TASK");
        var line = require(mapper.lockLine(enterprise, warehouse, String.valueOf(task.get("document_line_id"))), "UNKNOWN_LINE");
        var context = context(order, line, text(task, "source_location_id"), text(task, "target_location_id"), lotId);
        context.requireForAction("PICK");
        var result = new OutboundOrderService(session, clock).pickPartial(enterprise, warehouse, taskId, commandId, actor, qty, partId);
        bind(enterprise,warehouse,result,line,context,selection);
        if(selection!=null) new OutboundSerialService(session,clock).claim(enterprise,warehouse,text(result,"commandId"),taskId,
                text(line,"id"),text(line,"order_line_id"),context,selection,Boolean.TRUE.equals(result.get("replayed")));
        return result;
    }

    /** 发运只使用本桶已回执拣货减已受理发运的余额，避免异步乱序令实物先发而预占尚未转入。 */
    public Map<String, Object> ship(String enterprise, String warehouse, String orderId, String orderLineId,
            String commandId, String actor, BigDecimal qty, String partId, String locationId, String lotId) {
        var mapper = session.getMapper(OutboundOrderMapper.class);
        var order = require(mapper.lockOrder(enterprise, warehouse, orderId), "UNKNOWN_ORDER");
        var line = require(mapper.lockLineByOrderLine(enterprise, warehouse, orderId, orderLineId), "UNKNOWN_LINE");
        var context = context(order, line, locationId, null, lotId); context.requireForAction("SHIP");
        var result = new OutboundOrderService(session, clock).shipPartial(enterprise, warehouse, orderId, orderLineId,
                commandId, actor, qty, partId);
        bind(enterprise, warehouse, result, line, context);
        if (!Boolean.TRUE.equals(result.get("replayed")) && mapper.claimBucketShipment(enterprise, warehouse, text(line, "id"),
                locationId, lotId, qty, java.sql.Timestamp.from(clock.instant())) != 1)
            throw new OutboundException("PICK_POSTING_PENDING", "本暂存桶的已过账拣货量不足，请先完成库存同步");
        return result;
    }

    /** 取消未拣保存显式原桶，库存仍按原订单行验证释放额度；不是拣货/发运命令墓碑。 */
    public Map<String, Object> cancel(String enterprise, String warehouse, String orderId, String orderLineId,
            String commandId, String actor, String locationId, String lotId, BigDecimal requestedQty) {
        var mapper = session.getMapper(OutboundOrderMapper.class);
        var order = require(mapper.lockOrder(enterprise, warehouse, orderId), "UNKNOWN_ORDER");
        var line = require(mapper.lockLineByOrderLine(enterprise, warehouse, orderId, orderLineId), "UNKNOWN_LINE");
        var context = context(order, line, locationId, null, lotId); context.requireForAction("CANCEL");
        var result = new OutboundOrderService(session, clock).cancelUnpicked(enterprise, warehouse, orderId, orderLineId, commandId, actor, requestedQty);
        bind(enterprise, warehouse, result, line, context);
        return result;
    }

    /** 已去重PICK回执才创建桶额度；旧无上下文事实不猜测补桶。 */
    public void recordPickResult(String enterprise, String warehouse, String lineId, String commandId, BigDecimal qty) {
        var command = session.getMapper(com.lrj.wms.outbound.protocol.SourceMapper.class).getCommand(enterprise, warehouse, commandId);
        var body = RuntimeMessage.JSON.readTree(text(command, "payload_json"));
        if (!body.has("postingContext")) return;
        if (!body.path("outboundSchemaVersion").isIntegralNumber() || !body.path("outboundSchemaVersion").canConvertToInt() || body.path("outboundSchemaVersion").intValue() != (body.hasNonNull("serialExecution")?2:1))
            throw new com.lrj.wms.runtime.messaging.MessageRejectedException("UNSUPPORTED_OUTBOUND_SCHEMA");
        var context = RuntimeMessage.JSON.treeToValue(body.path("postingContext"), StockPostingContext.class);
        context.requireForAction("PICK");
        if (session.getMapper(OutboundOrderMapper.class).addBucketPicked(enterprise, warehouse, lineId,
                context.targetLocationId(), context.lotId(), qty, java.sql.Timestamp.from(clock.instant())) < 1)
            throw new OutboundException("VERSION_CONFLICT", "拣货回执额度更新冲突");
        if(body.hasNonNull("serialExecution")) {
            var selection=RuntimeMessage.JSON.treeToValue(body.path("serialExecution"),com.lrj.wms.contract.messaging.SerialExecutionSelection.class);
            selection.requireQuantity(qty);new OutboundSerialService(session,clock).posted(enterprise,warehouse,commandId,selection);
        }
    }

    private void bind(String enterprise, String warehouse, Map<String, Object> result, Map<String, Object> line, StockPostingContext context) {
        bind(enterprise,warehouse,result,line,context,null);
    }
    private void bind(String enterprise,String warehouse,Map<String,Object> result,Map<String,Object> line,StockPostingContext context,
            com.lrj.wms.contract.messaging.SerialExecutionSelection selection) {
        result.put("documentId",context.documentId());
        new SourceCommandContextStore(session).bindOutbound(enterprise,warehouse,text(result,"commandId"),context,
                text(line,"order_line_id"),selection,Boolean.TRUE.equals(result.get("replayed")));
    }
    private static StockPostingContext context(Map<String, Object> order, Map<String, Object> line, String source, String target, String lot) {
        return new StockPostingContext(text(order, "id"), text(order, "owner_id"), text(line, "sku_id"), text(line, "base_unit"),
                source, target, lot, "GOOD", text(order, "allocation_id"), text(order, "attempt_id"));
    }
    private static String text(Map<String, Object> row, String field) { Object value = row.get(field); return value == null ? null : value.toString(); }
    private static Map<String, Object> require(Map<String, Object> row, String code) {
        if (row == null) throw new OutboundException(code, "出库原始事实不存在"); return row;
    }
}
