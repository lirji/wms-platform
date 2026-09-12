package com.lrj.wms.runtime.messaging;

/** 明确不适合自动重试的契约错误；持久化隔离后才能提交消费位点。 */
public final class MessageRejectedException extends RuntimeException {
    private final String code;
    public MessageRejectedException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
