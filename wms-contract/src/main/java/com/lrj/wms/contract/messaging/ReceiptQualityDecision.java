package com.lrj.wms.contract.messaging;

import java.math.BigDecimal;

/** 收货分批的累计质检快照；未检数量继续留在HOLD，版本不能改变原收货身份。 */
public record ReceiptQualityDecision(String receiptCommandId, String inspectionId, long sourceVersion,
        BigDecimal acceptedQty, BigDecimal rejectedQty) {
    public ReceiptQualityDecision {
        if (receiptCommandId == null || receiptCommandId.isBlank() || receiptCommandId.length() > 64
                || inspectionId == null || inspectionId.isBlank() || inspectionId.length() > 64 || sourceVersion < 1
                || acceptedQty == null || rejectedQty == null || acceptedQty.signum() < 0 || rejectedQty.signum() < 0
                || acceptedQty.add(rejectedQty).signum() <= 0) throw new IllegalArgumentException("分批质检参数无效");
        // 3与3.0属于同一业务数量，规范化后重试摘要不受客户端小数格式影响。
        acceptedQty = acceptedQty.stripTrailingZeros(); rejectedQty = rejectedQty.stripTrailingZeros();
        if (acceptedQty.scale() > 6 || rejectedQty.scale() > 6
                || acceptedQty.compareTo(new BigDecimal("100000000000000")) >= 0
                || rejectedQty.compareTo(new BigDecimal("100000000000000")) >= 0) throw new IllegalArgumentException("质检数量超出存储范围");
    }
    /** 本版本已检累计量，不代表新增库存数量。 */
    public BigDecimal inspectedQty() { return acceptedQty.add(rejectedQty); }
}
