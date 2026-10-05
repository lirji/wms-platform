package com.lrj.wms.runtime.messaging.protocol;

/** 明确不适合自动重试的契约错误；持久化隔离后才能提交消费位点。 */
public final class MessageRejectedException extends RuntimeException {
    private final String code;

    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public MessageRejectedException(String code) {
        super(code);
        this.code = code;
    }

    /** 返回稳定协议码，持久化与外部响应不能依赖枚举序号或异常文本。 */
    public String code() {
        return code;
    }
}
