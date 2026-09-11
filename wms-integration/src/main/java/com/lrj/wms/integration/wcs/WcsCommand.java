package com.lrj.wms.integration.wcs;

import java.math.BigDecimal;
import java.util.Objects;

/** 一次设备动作命令。deviceCommandId 在派发前固定，崩溃后不得换号重派。 */
public record WcsCommand(String enterpriseId, String warehouseId, String deviceCommandId, String action,
        String sourceTaskId, BigDecimal qty, String digest) {
    public WcsCommand {
        require(enterpriseId, "enterpriseId");
        require(warehouseId, "warehouseId");
        require(deviceCommandId, "deviceCommandId");
        require(action, "action");
        require(sourceTaskId, "sourceTaskId");
        require(digest, "digest");
        if (qty == null || qty.signum() <= 0) {
            throw new WcsAdapterException("INVALID_QTY", "设备命令数量必须为正");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WcsAdapterException("INVALID_COMMAND", field + "不能为空");
        }
    }

    public String identityKey() {
        return enterpriseId + '\u001f' + warehouseId + '\u001f' + deviceCommandId;
    }

    public boolean samePayload(WcsCommand other) {
        return Objects.equals(action, other.action) && Objects.equals(sourceTaskId, other.sourceTaskId)
                && qty.compareTo(other.qty) == 0 && Objects.equals(digest, other.digest);
    }
}
