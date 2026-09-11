package com.lrj.wms.integration.wcs;

import java.math.BigDecimal;
import java.time.Instant;

/** 设备回执。同一 eventId 重放不得产生第二次业务效果。 */
public record WcsReceipt(String enterpriseId, String warehouseId, String deviceCommandId, String eventId,
        String resultState, BigDecimal actualQty, Instant observedAt) {
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
            throw new WcsAdapterException("INVALID_RECEIPT", "observedAt不能为空");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WcsAdapterException("INVALID_RECEIPT", field + "不能为空");
        }
    }

    public String identityKey() {
        return enterpriseId + '\u001f' + warehouseId + '\u001f' + deviceCommandId;
    }
}
