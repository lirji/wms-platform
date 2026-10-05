package com.lrj.wms.runtime.messaging.recovery;

/** 恢复冲突只公开稳定错误码，不把原始消息和数据库异常暴露给调用方。 */
public final class MessageRecoveryException extends RuntimeException {
    private final String code;

    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public MessageRecoveryException(String code) {
        super(code);
        this.code = code;
    }

    /** 返回稳定协议码，持久化与外部响应不能依赖枚举序号或异常文本。 */
    public String code() {
        return code;
    }
}
