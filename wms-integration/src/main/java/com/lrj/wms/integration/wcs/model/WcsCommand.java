package com.lrj.wms.integration.wcs.model;

import com.lrj.wms.integration.wcs.error.WcsAdapterException;

import java.math.BigDecimal;
import java.util.Objects;

/** 一次设备动作命令。deviceCommandId 在派发前固定，崩溃后不得换号重派。 */
public record WcsCommand(
        String enterpriseId,
        String warehouseId,
        String deviceCommandId,
        String action,
        String sourceTaskId,
        BigDecimal qty,
        String digest) {
    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
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

    /** 以企业及仓等身份字段组合去重键，避免不同业务作用域互相覆盖。 */
    public String identityKey() {
        return enterpriseId + '\u001f' + warehouseId + '\u001f' + deviceCommandId;
    }

    /** 比较重派命令的有效载荷，拒绝同一命令身份替换内容。 */
    public boolean samePayload(WcsCommand other) {
        return Objects.equals(action, other.action)
                && Objects.equals(sourceTaskId, other.sourceTaskId)
                && qty.compareTo(other.qty) == 0
                && Objects.equals(digest, other.digest);
    }
}
