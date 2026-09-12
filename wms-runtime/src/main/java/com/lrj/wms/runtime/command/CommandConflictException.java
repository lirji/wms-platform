package com.lrj.wms.runtime.command;

/** 同一幂等身份不能承载不同业务事实或数量；调用方应更正请求而不是盲目重试。 */
public final class CommandConflictException extends RuntimeException {
    public CommandConflictException() { super("同一命令或事实身份的内容不一致"); }
}
