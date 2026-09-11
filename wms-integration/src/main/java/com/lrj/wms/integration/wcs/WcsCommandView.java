package com.lrj.wms.integration.wcs;

import java.math.BigDecimal;
import java.time.Instant;

/** 设备命令查询视图。查不到时由调用方视为 UNKNOWN，不能据此换新命令号。 */
public record WcsCommandView(String deviceCommandId, String state, String action, String sourceTaskId,
        BigDecimal qty, String receiptEventId, Instant updatedAt) {
}
