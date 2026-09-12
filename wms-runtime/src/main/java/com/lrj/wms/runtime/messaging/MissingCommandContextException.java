package com.lrj.wms.runtime.messaging;

/** 历史命令缺少原始维度时只能走有证据的人工核对，不能把本次请求当成历史事实。 */
public final class MissingCommandContextException extends RuntimeException {
    public MissingCommandContextException() { super("历史命令缺少原始库存维度"); }
}
