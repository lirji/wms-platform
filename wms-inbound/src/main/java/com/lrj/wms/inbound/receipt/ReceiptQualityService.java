package com.lrj.wms.inbound.receipt;

import com.lrj.wms.contract.messaging.ReceiptQualityDecision;
import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.inbound.protocol.SourceProtocolService;
import com.lrj.wms.runtime.command.CommandConflictException;
import com.lrj.wms.runtime.command.CommandKeys;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.runtime.messaging.SourceCommandContextStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 每批质检累计快照：在原收货行锁内校验版本和已上架量，业务与来源命令同事务。 */
public final class ReceiptQualityService {
    private final SqlSession session;
    private final Clock clock;
    public ReceiptQualityService(SqlSession session, Clock clock) { this.session = session; this.clock = clock; }

    /** 批次选择只返回本单据的有界元数据，不暴露来源命令原始正文。 */
    public Map<String, Object> batches(String ent, String wh, String order, Integer limit, String cursor) {
        if (session.getMapper(InboundReceiptMapper.class).getOrder(ent, wh, order) == null) throw new InboundException("RESOURCE_NOT_FOUND", "入库单不存在");
        var page = com.lrj.wms.runtime.web.CursorPage.parse(limit, cursor,
                com.lrj.wms.runtime.web.CursorPage.scope("receipt-batches", ent, wh, order));
        var rows = session.getMapper(ReceiptQualityMapper.class).batches(ent, wh, order, page);
        for (var row : rows) {
            var payload = RuntimeMessage.JSON.readTree(String.valueOf(row.remove("payload_json")));
            row.put("receiptCommandId", row.get("id")); row.put("qty", payload.path("qty").asString());
            var context = payload.path("postingContext"); row.put("contextAvailable", context.isObject());
            if (context.isObject()) {
                row.put("locationId", context.path("sourceLocationId").asString());
                row.put("lotId", context.path("lotId").asString()); row.put("skuId", context.path("skuId").asString());
            }
        }
        return page.result(rows, false);
    }

    /** 只接受已过账批次，待确认版本不能被下一版本越过；重试保持原命令和操作者。 */
    public Map<String, Object> inspect(String ent, String wh, String lineId, String command, String actor,
            ReceiptQualityDecision decision) {
        CommandKeys.resolve(command, null);
        var mapper = session.getMapper(ReceiptQualityMapper.class);
        var receipt = mapper.receipt(ent, wh, decision.receiptCommandId());
        if (receipt == null || !lineId.equals(receipt.get("line_id"))) throw new InboundException("UNKNOWN_RECEIPT_BATCH", "收货批次不属于该行");
        if (session.getMapper(InboundReceiptMapper.class).lockLine(ent, wh, lineId) == null) throw new InboundException("UNKNOWN_LINE", "入库行不存在");
        var protocol = new SourceProtocolService(session, clock);
        String hash = RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(decision));
        var revisions = mapper.revisions(ent, wh, decision.receiptCommandId(), decision.inspectionId(), command, decision.sourceVersion());
        if (!revisions.isEmpty()) {
            if (revisions.size() != 1 || !hash.equals(revisions.getFirst().get("request_hash"))) throw new CommandConflictException();
            var replay = protocol.get(ent, wh, String.valueOf(revisions.getFirst().get("command_id")));
            replay.put("replayed", true); replay.put("inspectionId", decision.inspectionId());
            return replay;
        }
        if (!"APPLIED".equals(receipt.get("state"))) throw new InboundException("RECEIPT_NOT_POSTED", "收货过账确认后才能质检");
        var payload = RuntimeMessage.JSON.readTree(String.valueOf(receipt.get("payload_json")));
        if (!payload.hasNonNull("postingContext")) throw new InboundException("MISSING_POSTING_CONTEXT", "历史收货缺少可信库存维度");
        StockPostingContext context = RuntimeMessage.JSON.treeToValue(payload.path("postingContext"), StockPostingContext.class);
        context.requireForAction("RECEIVE");
        if (!context.documentId().equals(receipt.get("order_id")) || !context.skuId().equals(receipt.get("sku_id"))
                || !context.ownerId().equals(receipt.get("owner_id"))) throw new InboundException("RECEIPT_CONTEXT_MISMATCH", "原收货维度不一致");
        BigDecimal received = new BigDecimal(payload.path("qty").asString());
        if (decision.inspectedQty().compareTo(received) > 0) throw new InboundException("INSPECT_EXCEEDS_RECEIVED", "质检超过该收货批次数量");
        Timestamp now = Timestamp.from(clock.instant());
        mapper.initialize(UUID.randomUUID().toString(), ent, wh, decision.receiptCommandId(), lineId, now);
        var state = mapper.lock(ent, wh, decision.receiptCommandId());
        if (((Number) state.get("source_version")).longValue() + 1 != decision.sourceVersion()
                || !state.get("source_version").equals(state.get("applied_version"))) {
            throw new InboundException("QUALITY_VERSION_CONFLICT", "须等待前一版本生效并提交下一连续版本");
        }
        if (new BigDecimal(state.get("putaway_qty").toString()).compareTo(decision.acceptedQty()) > 0) {
            throw new InboundException("QUALITY_ALREADY_PUTAWAY", "新合格量不能低于已经确认上架的数量");
        }
        var result = protocol.submitQuality(ent, wh, command, decision.receiptCommandId(), Long.toString(decision.sourceVersion()), lineId, actor, decision);
        if (Boolean.TRUE.equals(result.get("replayed"))) throw new CommandConflictException();
        new SourceCommandContextStore(session).bind(ent, wh, command, context, false);
        if (mapper.insertRevision(ent, wh, decision.receiptCommandId(), decision.inspectionId(), command, decision.sourceVersion(), hash,
                actor, decision.acceptedQty(), decision.rejectedQty(), now) != 1
                || mapper.accept(ent, wh, decision.receiptCommandId(), command, decision.sourceVersion(), ((Number) state.get("version")).longValue(),
                        decision.acceptedQty(), decision.rejectedQty(), now) != 1) throw new InboundException("VERSION_CONFLICT", "质检版本受理竞争");
        result.put("inspectionId", decision.inspectionId()); result.put("receiptCommandId", decision.receiptCommandId());
        return result;
    }

    /** 只有库存原子过账的可靠回执才让该版本可用于上架。 */
    public void consume(String ent, String wh, String line, String event, String command, String state, String posting, BigDecimal qty) {
        var protocol = new SourceProtocolService(session, clock);
        protocol.requireResultFact(ent, wh, command, "QUALITY", line);
        if (session.getMapper(InboundReceiptMapper.class).lockLine(ent, wh, line) == null) throw new InboundException("UNKNOWN_LINE", "质检行不存在");
        var result = protocol.consumeResult(ent, wh, event, command, state, posting, qty);
        if (Boolean.TRUE.equals(result.get("consumed")) && session.getMapper(ReceiptQualityMapper.class)
                .applied(ent, wh, command, state, Timestamp.from(clock.instant())) != 1) {
            throw new InboundException("VERSION_CONFLICT", "质检回执不属于当前待确认版本");
        }
    }
}
