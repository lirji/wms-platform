package com.lrj.wms.inventory.quality;

import com.lrj.wms.contract.messaging.ReceiptQualityDecision;
import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.session.SqlSession;

/** 质检只在原批次各质量桶之间守恒转换；已上架数量不能被新版本重新判为不合格。 */
public final class ReceiptQualityStockService {
    private final SqlSession session;
    private final Clock clock;
    public ReceiptQualityStockService(SqlSession session, Clock clock) { this.session = session; this.clock = clock; }

    /** 调用方负责库存命令与Inbox事务；本方法不做跨库调用或独立提交。 */
    public void apply(String ent, String wh, String operation, String document, String actor,
            StockBucketKey hold, ReceiptQualityDecision decision) {
        var mapper = session.getMapper(ReceiptQualityStockMapper.class);
        var receipt = mapper.receipt(ent, wh, decision.receiptCommandId());
        if (receipt == null) throw new InventoryException("RECEIPT_NOT_POSTED", "原收货凭证尚不存在");
        if (!document.equals(receipt.get("source_document_id")) || !hold.ownerId().equals(receipt.get("owner_id"))
                || !hold.locationId().equals(receipt.get("location_id")) || !hold.skuId().equals(receipt.get("sku_id"))
                || !hold.lotId().equals(receipt.get("lot_id")) || !"HOLD".equals(receipt.get("quality_code"))) {
            throw new InventoryException("RECEIPT_CONTEXT_MISMATCH", "质检维度不属于原收货库存");
        }
        if (decision.inspectedQty().compareTo(decimal(receipt.get("quantity"))) > 0) throw new InventoryException("INSPECT_EXCEEDS_RECEIVED", "质检超过原批数量");
        Timestamp now = Timestamp.from(clock.instant());
        mapper.initialize(UUID.randomUUID().toString(), ent, wh, decision.receiptCommandId(), now);
        var current = mapper.lock(ent, wh, decision.receiptCommandId());
        if (((Number) current.get("source_version")).longValue()+1 != decision.sourceVersion()) throw new InventoryException("QUALITY_VERSION_CONFLICT", "质检版本不连续");
        if (decision.acceptedQty().compareTo(decimal(current.get("putaway_qty"))) < 0) throw new InventoryException("QUALITY_ALREADY_PUTAWAY", "新合格量低于已上架数量");
        BigDecimal goodDelta = decision.acceptedQty().subtract(decimal(current.get("accepted_qty")));
        BigDecimal rejectedDelta = decision.rejectedQty().subtract(decimal(current.get("rejected_qty")));
        new InventoryApplicationService(session, clock).reclassifyQuality(ent, wh, operation, document, actor, hold,
                Map.of("HOLD", goodDelta.add(rejectedDelta).negate(), "GOOD", goodDelta, "REJECTED", rejectedDelta));
        if (mapper.apply(ent, wh, decision.receiptCommandId(), decision.sourceVersion(), ((Number) current.get("version")).longValue(),
                decision.acceptedQty(), decision.rejectedQty(), now) != 1) throw new InventoryException("VERSION_CONFLICT", "质检并发状态变更");
    }
    private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
}
