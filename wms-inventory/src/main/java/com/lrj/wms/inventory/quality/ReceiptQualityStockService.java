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
    /** 在上架转桶的同一事务消耗本批合格量；不能以同桶其他批次的余额替代本批额度。 */
    public void putaway(String ent, String wh, String receiptCommand, String document, StockBucketKey source, BigDecimal qty) {
        var mapper = session.getMapper(ReceiptQualityStockMapper.class);
        var receipt = mapper.receipt(ent, wh, receiptCommand);
        if (receipt == null || !document.equals(receipt.get("source_document_id")) || !source.ownerId().equals(receipt.get("owner_id"))
                || !source.locationId().equals(receipt.get("location_id")) || !source.skuId().equals(receipt.get("sku_id"))
                || !source.lotId().equals(receipt.get("lot_id")) || !"HOLD".equals(receipt.get("quality_code")) || !"GOOD".equals(source.qualityCode()))
            throw new InventoryException("RECEIPT_CONTEXT_MISMATCH", "上架库存不属于原收货批次");
        var quality = mapper.lock(ent, wh, receiptCommand);
        if (quality == null || mapper.addPutaway(ent, wh, receiptCommand, ((Number) quality.get("version")).longValue(), qty,
                Timestamp.from(clock.instant())) != 1) throw new InventoryException("QC_INSUFFICIENT_ACCEPTED", "该批已生效合格量不足");
    }

    private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
}
