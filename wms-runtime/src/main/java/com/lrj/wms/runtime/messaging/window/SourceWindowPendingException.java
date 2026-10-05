package com.lrj.wms.runtime.messaging.window;

/** 来源回执尚未齐全属于可恢复业务状态，不能作为内部服务异常暴露。 */
public final class SourceWindowPendingException extends IllegalStateException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public SourceWindowPendingException() {
        super("来源尚未完成关窗");
    }
}
