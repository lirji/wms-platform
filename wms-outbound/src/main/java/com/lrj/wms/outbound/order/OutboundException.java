package com.lrj.wms.outbound.order;

/** 出库单据冲突，携带稳定错误码。 */
public final class OutboundException extends RuntimeException {
    private final String code;

    public OutboundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
