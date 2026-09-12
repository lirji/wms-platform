package com.lrj.wms.runtime.messaging;

/** 来源回执尚未齐全属于可恢复业务状态，不能作为内部服务异常暴露。 */
public final class SourceWindowPendingException extends IllegalStateException {
    public SourceWindowPendingException() {super("来源尚未完成关窗");}
}
