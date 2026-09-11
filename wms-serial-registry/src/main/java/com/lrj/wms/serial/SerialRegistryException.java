package com.lrj.wms.serial;

/** 序列号登记冲突，携带稳定错误码。 */
public final class SerialRegistryException extends RuntimeException {
    private final String code;

    public SerialRegistryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
