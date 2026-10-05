package com.lrj.wms.inventory.inventory.domain;

import java.time.Instant;

/**
 * 实时效期。有效区间左闭右开：now &lt; expires_at 才满足；时刻为空表示未绑定失效。
 * DATETIME 由显式配置的 JDBC 边界还原 Instant，禁止依赖 JVM 默认时区。
 */
public final class ExpiryPolicy {
    private ExpiryPolicy() {}

    /** 有效区间左闭右开；未绑定失效时刻可以通过，但调用方必须提供判定时刻。 */
    public static boolean satisfied(Instant expiresAt, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("判定时刻不能为空");
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }
}
