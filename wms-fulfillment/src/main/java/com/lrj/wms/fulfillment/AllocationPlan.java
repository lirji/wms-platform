package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 候选仓分配冻结：参与仓、行数量与摘要在 Try 前固定。
 * 不选择隐式默认仓，不发明 OQ-03 单位/效期规则。
 */
public final class AllocationPlan {
    public static final int DIGEST_VERSION = 1;

    private AllocationPlan() {
    }

    /** 规范摘要：仓、履约行、SKU、数量、单位。顺序固定。 */
    public static String digest(List<Map<String, Object>> participantLines) {
        List<Map<String, Object>> rows = new ArrayList<>(participantLines);
        rows.sort(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("warehouseId")))
                .thenComparing(row -> String.valueOf(row.get("orderLineId")))
                .thenComparing(row -> String.valueOf(row.get("skuId"))));
        StringBuilder canonical = new StringBuilder("alloc-v").append(DIGEST_VERSION);
        for (Map<String, Object> row : rows) {
            canonical.append('\u001f').append(row.get("warehouseId"))
                    .append('\u001f').append(row.get("orderLineId"))
                    .append('\u001f').append(row.get("skuId"))
                    .append('\u001f').append(plainQty(row.get("qty")))
                    .append('\u001f').append(row.get("baseUnit"));
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String plainQty(Object value) {
        BigDecimal qty = value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
        return qty.stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
    }
}
