package com.lrj.wms.contract.messaging;

import java.math.BigDecimal;
import java.util.*;

/** 每次收货的完整身份观察；规范化后不可变，数量不能代替明确的序列号清单。 */
public record SerialReceiptObservation(int schemaVersion, List<String> serialIds) {
    public SerialReceiptObservation {
        if (schemaVersion != 1 || serialIds == null || serialIds.isEmpty() || serialIds.size() > 200)
            throw new IllegalArgumentException("序列号观察须为V1且包含1至200个身份");
        var canonical = new TreeSet<String>();
        for (String serial : serialIds) {
            if (serial == null || serial.isBlank() || serial.length() > 64)
                throw new IllegalArgumentException("序列号必须非空且不超过64字符");
            String normalized = serial.trim().toUpperCase(Locale.ROOT);
            if (normalized.length() > 64 || !canonical.add(normalized))
                throw new IllegalArgumentException("规范化序列号超长或重复");
        }
        serialIds = List.copyOf(canonical);
    }

    /** 序列号SKU每个身份对应一个基本单位，不允许分数或未观测的隐含数量。 */
    public void requireQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.valueOf(serialIds.size())) != 0)
            throw new IllegalArgumentException("收货数量必须等于完整序列号清单长度");
    }
}
