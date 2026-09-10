package com.lrj.wms.inventory.effect.domain;

/** 效果协议冲突，携带稳定错误码。 */
public final class EffectProtocolException extends RuntimeException {
    private final String code;

    public EffectProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
