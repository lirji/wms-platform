package com.lrj.wms.contract.messaging;

/**
 * 库存命令的显式过账维度；来源库在T1保存，库存库按权威主数据再次验证。
 * 这里只描述公共契约，不导入余额、来源订单或任何Mapper。
 */
public record StockPostingContext(String documentId, String ownerId, String skuId, String baseUnit, String sourceLocationId,
        String targetLocationId, String lotId, String qualityCode, String allocationId, String allocationAttemptId) {
    public StockPostingContext {
        required(documentId, 64); required(ownerId, 64); required(skuId, 64); required(baseUnit, 32);
        required(sourceLocationId, 64); required(lotId, 64); required(qualityCode, 32);
        optional(targetLocationId); optional(allocationId); optional(allocationAttemptId);
        if (!java.util.Set.of("GOOD", "HOLD", "REJECTED").contains(qualityCode)) {
            throw new IllegalArgumentException("未知库存质量代码");
        }
    }

    /** 按动作约束必需维度，不能把缺库位、批次或分配身份默认为任意可用库存。 */
    public void requireForAction(String action) {
        switch (action) {
            case "RECEIVE", "QUALITY" -> {
                if (!"HOLD".equals(qualityCode) || targetLocationId != null || allocationId != null || allocationAttemptId != null) {
                    throw new IllegalArgumentException("收货必须进入明确的HOLD桶");
                }
            }
            case "PUTAWAY" -> {
                required(targetLocationId, 64);
                if (!"GOOD".equals(qualityCode) || sourceLocationId.equals(targetLocationId)) {
                    throw new IllegalArgumentException("上架需要不同的来源/目标库位和合格质量");
                }
            }
            case "PICK", "SHIP", "CANCEL" -> {
                required(allocationId, 64); required(allocationAttemptId, 64);
                if (!"GOOD".equals(qualityCode)) throw new IllegalArgumentException("出库需要合格库存");
                if ("PICK".equals(action)) {
                    required(targetLocationId, 64);
                    if (sourceLocationId.equals(targetLocationId)) throw new IllegalArgumentException("拣货源目标不能相同");
                } else if (targetLocationId != null) throw new IllegalArgumentException("发运或取消没有目标桶");
            }
            default -> throw new IllegalArgumentException("尚未定义该动作的库存过账契约");
        }
    }
    private static void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("库存过账维度缺失或超长");
    }
    private static void optional(String value) { if (value != null) required(value, 64); }
}
