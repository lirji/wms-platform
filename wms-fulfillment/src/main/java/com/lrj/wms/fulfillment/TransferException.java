package com.lrj.wms.fulfillment;

/** 调拨业务错误。 */
public final class TransferException extends RuntimeException {
    private final String code;

    public TransferException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
