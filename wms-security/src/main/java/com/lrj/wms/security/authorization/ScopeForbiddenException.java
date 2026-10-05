package com.lrj.wms.security.authorization;

/** 令牌缺少接口要求的 scope。 */
public final class ScopeForbiddenException extends RuntimeException {
    /** 携带本异常既有的错误身份与说明，边界转换使用稳定结果而非堆栈文本。 */
    public ScopeForbiddenException(String scope) {
        super("SCOPE_FORBIDDEN:" + scope);
    }
}
