package com.lrj.wms.inbound.receipt;

/** 入库单据冲突，携带稳定错误码。 */
public final class InboundException extends RuntimeException {
    private final String code;

    public InboundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
