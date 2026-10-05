package com.lrj.wms.inventory.inventory.outbox;

/** 毒消息：停止自动重试，写入 ISOLATED，不得绕过继续投递后续依赖事件。 */
public final class OutboxIsolateException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public OutboxIsolateException(String message) {
        super(message);
    }
}
