package com.lrj.wms.inventory.inventory.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Outbox 运维参数；有界次数和指数退避抖动，防止多个实例同步重试。 */
@ConfigurationProperties("wms.outbox")
public record OutboxBudget(
        @DefaultValue("32") int batchSize,
        @DefaultValue("8") int maxClaims,
        @DefaultValue("30") int leaseSeconds,
        @DefaultValue("1000") long baseDelayMs,
        @DefaultValue("60000") long maxDelayMs) {
    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public OutboxBudget {
        if (batchSize < 1
                || batchSize > 200
                || maxClaims < 2
                || maxClaims > 20
                || leaseSeconds < 5
                || leaseSeconds > 300
                || baseDelayMs < 100
                || maxDelayMs < baseDelayMs
                || maxDelayMs > 300000) {
            throw new IllegalArgumentException("Outbox 重试与租约配置无效");
        }
    }

    /** 集中提供既有 Outbox 默认预算，调用方不能各自引入不同的重试上限。 */
    public static OutboxBudget defaults() {
        return new OutboxBudget(32, 8, 30, 1000, 60000);
    }

    /** 按当前领取代际计算有界重试间隔，不能立即无限重试失败投递。 */
    public Duration retryDelay(long claimEpoch) {
        long cap =
                Math.min(maxDelayMs, baseDelayMs * (1L << Math.min(20, Math.max(0, claimEpoch))));
        return Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(Math.max(1, cap / 2), cap + 1));
    }
}
