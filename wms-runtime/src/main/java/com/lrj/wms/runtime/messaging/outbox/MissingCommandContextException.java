package com.lrj.wms.runtime.messaging.outbox;

/** 历史命令缺少原始维度时只能走有证据的人工核对，不能把本次请求当成历史事实。 */
public final class MissingCommandContextException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public MissingCommandContextException() {
        super("历史命令缺少原始库存维度");
    }
}
