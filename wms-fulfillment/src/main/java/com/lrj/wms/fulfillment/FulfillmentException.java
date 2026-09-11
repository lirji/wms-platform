package com.lrj.wms.fulfillment;

/** 履约映射冲突，携带稳定错误码。 */
public final class FulfillmentException extends RuntimeException {
    private final String code;

    public FulfillmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 稳定错误码，供调用方映射恢复路径。 */
    public String code() {
        return code;
    }
}
