package com.lrj.wms.integration.wcs.model;

import com.lrj.wms.integration.wcs.error.WcsAdapterException;

/** 设备命令观察的封闭集合；协议使用显式 code，状态的名称和顺序不承担序列化语义。 */
public enum WcsCommandState {
    DISPATCHED("DISPATCHED"),
    UNKNOWN("UNKNOWN"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private final String code;

    WcsCommandState(String code) {
        this.code = code;
    }

    /** 输出已发布的协议值，使内部枚举调整不改变响应字段。 */
    public String code() {
        return code;
    }

    /** 仅既有三种回执状态可进入处理；派发受理不能冒充设备回执。 */
    public static WcsCommandState receiptState(String code) {
        return switch (code) {
            case "FAILED" -> FAILED;
            case "UNKNOWN" -> UNKNOWN;
            case "COMPLETED" -> COMPLETED;
            default ->
                    throw new WcsAdapterException(WcsAdapterException.INVALID_RECEIPT, "不支持的回执状态");
        };
    }
}
