package com.lrj.wms.runtime.messaging;

/** 恢复冲突只公开稳定错误码，不把原始消息和数据库异常暴露给调用方。 */
public final class MessageRecoveryException extends RuntimeException {
    private final String code;
    public MessageRecoveryException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
