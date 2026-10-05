package com.lrj.wms.runtime.command;

/** 同一幂等身份不能承载不同业务事实或数量；调用方应更正请求而不是盲目重试。 */
public final class CommandConflictException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public CommandConflictException() {
        super("同一命令或事实身份的内容不一致");
    }
}
