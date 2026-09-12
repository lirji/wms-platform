package com.lrj.wms.contract.messaging;

import java.math.BigDecimal;
import java.util.List;

/** 一次盘点的完整实见集合；空集合明确表示一件未见，不与未提交身份混淆。 */
public record SerialCountObservation(Number schemaVersion,List<String> serialIds) {
    public SerialCountObservation {
        if(!(schemaVersion instanceof Integer || schemaVersion instanceof Long) || schemaVersion.longValue()!=1 || serialIds==null || serialIds.size()>200) throw new IllegalArgumentException("盘点身份须为V1且包含0至200个身份");
        // 保留原数值类型再校验，避免JSON小数版本先被强转成1；输出统一使用整数1。
        schemaVersion=1;
        serialIds=serialIds.isEmpty()?List.of():new SerialReceiptObservation(1,serialIds).serialIds();
    }
    /** 每个序列号对应一个基本单位，拒绝用数量掩盖缺失或重复的身份。 */
    public void requireQuantity(BigDecimal quantity) {
        if(quantity==null || quantity.compareTo(BigDecimal.valueOf(serialIds.size()))!=0)
            throw new IllegalArgumentException("盘点数量必须等于完整实见身份数");
    }
}
