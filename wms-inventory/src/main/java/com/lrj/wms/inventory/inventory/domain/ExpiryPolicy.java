package com.lrj.wms.inventory.inventory.domain;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 实时效期。有效区间左闭右开：now &lt; expires_at 才满足；时刻为空表示未绑定失效。
 * DATETIME 按 JDBC 本地墙钟还原 Instant，与查询接口一致。
 */
public final class ExpiryPolicy {
    private ExpiryPolicy() {
    }

    public static boolean satisfied(Instant expiresAt, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("判定时刻不能为空");
        }
        return expiresAt == null || now.isBefore(expiresAt);
    }

    public static Instant instantOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        throw new IllegalArgumentException("无法识别的效期类型：" + value.getClass().getName());
    }
}
