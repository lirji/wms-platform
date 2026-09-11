package com.lrj.wms.integration.wcs;

/** WCS 适配冲突，携带稳定错误码。 */
public final class WcsAdapterException extends RuntimeException {
    private final String code;

    public WcsAdapterException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
