package com.lrj.wms.security.authorization;

/** 中央拒绝与不可判定故障分别建模；不把上游异常原文或凭据带到HTTP边界。 */
final class CentralAuthorizationException extends RuntimeException {
    enum Reason {
        UNAUTHENTICATED(401, "UNAUTHENTICATED", "请重新登录", false),
        DENIED(403, "CENTRAL_ACCESS_DENIED", "无权执行此操作或访问该仓", false),
        UNAVAILABLE(503, "AUTHORIZATION_UNAVAILABLE", "权限服务暂不可用，请稍后重试", true);
        final int status;
        final String code;
        final String message;
        final boolean retryable;

        Reason(int status, String code, String message, boolean retryable) {
            this.status = status;
            this.code = code;
            this.message = message;
            this.retryable = retryable;
        }
    }

    final Reason reason;

    CentralAuthorizationException(Reason reason) {
        super(reason.code);
        this.reason = reason;
    }
}
