package com.lrj.wms.inventory.masterdata;

/** 主数据冲突或缺失，携带稳定错误码。 */
public final class MasterdataException extends RuntimeException {
    private final String code;

    public MasterdataException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
