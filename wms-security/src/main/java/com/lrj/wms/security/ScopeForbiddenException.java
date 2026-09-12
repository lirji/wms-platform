package com.lrj.wms.security;

/** 令牌缺少接口要求的 scope。 */
public final class ScopeForbiddenException extends RuntimeException {
    public ScopeForbiddenException(String scope) {
        super("SCOPE_FORBIDDEN:" + scope);
    }
}
