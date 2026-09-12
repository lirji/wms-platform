package com.lrj.wms.runtime.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 主数据展示缓存策略；权限和库存决策禁止使用本缓存。机密不进入 toString。 */
@ConfigurationProperties("wms.runtime.cache")
public record QueryCacheProperties(@DefaultValue("") String redisHost, @DefaultValue("6379") int redisPort,
        @DefaultValue("") String redisPassword, @DefaultValue("false") boolean redisTls,
        @DefaultValue("wms:local:masterdata:v1:") String namespace,
        @DefaultValue("5000") long maxAgeMs, @DefaultValue("500") long localTtlMs,
        @DefaultValue("1000") int maximumEntries, @DefaultValue("4") int originConcurrency,
        @DefaultValue("100") long redisTimeoutMs) {
    public QueryCacheProperties {
        if (redisHost == null || redisPassword == null || namespace == null || !namespace.matches("[a-zA-Z0-9:_-]{1,100}")
                || redisPort < 1 || redisPort > 65535 || maxAgeMs < 100 || maxAgeMs > 5000
                || localTtlMs < 1 || localTtlMs > maxAgeMs || maximumEntries < 1 || maximumEntries > 10000
                || originConcurrency < 1 || originConcurrency > 16 || redisTimeoutMs < 10 || redisTimeoutMs > 500) {
            throw new IllegalArgumentException("缓存容量、命名空间或超时配置不合法");
        }
    }
    @Override public String toString() { return "QueryCacheProperties[credentials=REDACTED]"; }
}
