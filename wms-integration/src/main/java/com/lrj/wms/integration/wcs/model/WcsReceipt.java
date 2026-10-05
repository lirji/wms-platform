package com.lrj.wms.integration.wcs.model;

import com.lrj.wms.integration.wcs.error.WcsAdapterException;

import java.math.BigDecimal;
import java.time.Instant;

/** 设备回执。同一 eventId 重放不得产生第二次业务效果。 */
public record WcsReceipt(
        String enterpriseId,
        String warehouseId,
        String deviceCommandId,
        String eventId,
        String resultState,
        BigDecimal actualQty,
        Instant observedAt) {
    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public WcsReceipt {
        require(enterpriseId, "enterpriseId");
        require(warehouseId, "warehouseId");
        require(deviceCommandId, "deviceCommandId");
        require(eventId, "eventId");
        require(resultState, "resultState");
        if (actualQty == null || actualQty.signum() < 0) {
            throw new WcsAdapterException("INVALID_QTY", "回执数量不能为负");
        }
        if (observedAt == null) {
            throw new WcsAdapterException(WcsAdapterException.INVALID_RECEIPT, "observedAt不能为空");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WcsAdapterException(WcsAdapterException.INVALID_RECEIPT, field + "不能为空");
        }
    }

    /** 以企业及仓等身份字段组合去重键，避免不同业务作用域互相覆盖。 */
    public String identityKey() {
        return enterpriseId + '\u001f' + warehouseId + '\u001f' + deviceCommandId;
    }
}
