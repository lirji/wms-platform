package com.lrj.wms.contract.serial.selection;

import com.lrj.wms.contract.serial.observation.SerialReceiptObservation;

import java.math.BigDecimal;
import java.util.List;

/** 本次库存动作实际选择的身份；与原收货完整清单分开，允许分次上架或拣货。 */
public record SerialStockSelection(int schemaVersion, List<String> serialIds) {
    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public SerialStockSelection {
        var normalized = new SerialReceiptObservation(schemaVersion, serialIds);
        serialIds = normalized.serialIds();
    }

    /** 每个身份一个基本单位，不能隐含未选择的数量。 */
    public void requireQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.valueOf(serialIds.size())) != 0)
            throw new IllegalArgumentException("动作数量与所选身份数量不一致");
    }
}
