package com.lrj.wms.runtime.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import tools.jackson.databind.json.JsonMapper;

/** 有界 L1/L2 主数据展示缓存。缓存读取前完成授权；所有写入和强一致读绕过本类。 */
public final class ReadQueryCache implements AutoCloseable, io.micrometer.core.instrument.binder.MeterBinder {
    private static final int MAX_VALUE_BYTES = 262144;
    private final QueryCacheProperties properties;
    private final Cache<String, Entry> local;
    private final RedisQueryStore redis;
    private final Semaphore origin;
    private final JsonMapper json = JsonMapper.builder().build();
    private final java.util.concurrent.atomic.LongAdder l2Hits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder originLoads = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder rejected = new java.util.concurrent.atomic.LongAdder();

    public ReadQueryCache(QueryCacheProperties properties) {
        this.properties = properties;
        this.local = Caffeine.newBuilder().maximumWeight(properties.maximumEntries() * 16384L)
                .weigher((String key, Entry entry) -> Math.max(16384, entry.value().getBytes(StandardCharsets.UTF_8).length))
                .expireAfterWrite(Duration.ofMillis(properties.localTtlMs())).recordStats().build();
        this.redis = new RedisQueryStore(properties);
        this.origin = new Semaphore(properties.originConcurrency());
    }

    /** 同 key 回源合并；scope 必须覆盖企业、仓、身份权限集合、筛选与游标。返回值独立反序列化避免相互修改。 */
    public Map<String, Object> read(String scope, Supplier<Map<String, Object>> loader) {
        String key = properties.namespace() + digest(scope);
        Entry entry = local.getIfPresent(key);
        if (entry != null && entry.expiresAt() <= System.currentTimeMillis()) { local.invalidate(key); entry = null; }
        if (entry == null) entry = local.get(key, ignored -> load(key, loader));
        if (entry.expiresAt() <= System.currentTimeMillis()) local.invalidate(key);
        return json.readValue(entry.value(), Map.class);
    }

    private Entry load(String key, Supplier<Map<String, Object>> loader) {
        String cached = redis.get(key);
        if (cached != null && cached.length() <= MAX_VALUE_BYTES) {
            try {
                Entry entry = json.readValue(cached, Entry.class);
                long remaining = entry.expiresAt() - System.currentTimeMillis();
                if (entry.value() != null && remaining > 0 && remaining <= properties.maxAgeMs()) {
                    if (json.readValue(entry.value(), Map.class) == null) throw new IllegalArgumentException("缓存不是对象");
                    l2Hits.increment();
                    return entry;
                }
            } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) { /* 无效缓存作为 miss，不能变成业务失败。 */ }
        }
        if (!origin.tryAcquire()) { rejected.increment(); throw new QueryCacheBusyException(); }
        try {
            originLoads.increment();
            // 从查询开始计算陈旧上界；慢查询不能在旧快照完成后重新获得完整 TTL。
            long started = System.currentTimeMillis();
            String value = json.writeValueAsString(loader.get());
            long age = properties.maxAgeMs() - ThreadLocalRandom.current().nextLong(Math.max(1, properties.maxAgeMs() / 5));
            Entry entry = new Entry(started + age, value);
            String encoded = json.writeValueAsString(entry);
            if (encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_VALUE_BYTES) {
                redis.put(key, encoded, entry.expiresAt() - System.currentTimeMillis());
            } else {
                // 大值只响应本次调用，不驻留任一缓存层。
                entry = new Entry(0, value);
            }
            return entry;
        } finally { origin.release(); }
    }

    /** 运维指标仅聚合缓存层，不以租户/游标产生高基数标签。 */
    public Map<String, Number> stats() {
        return Map.of("l1Hits", local.stats().hitCount(), "l2Hits", l2Hits.sum(), "originLoads", originLoads.sum(),
                "originRejected", rejected.sum(), "evictions", local.stats().evictionCount(), "entries", local.estimatedSize(), "l2Errors", redis.errors());
    }

    @Override public void bindTo(io.micrometer.core.instrument.MeterRegistry registry) {
        for (String name : java.util.List.of("l1Hits", "l2Hits", "originLoads", "originRejected", "evictions", "l2Errors")) {
            io.micrometer.core.instrument.FunctionCounter.builder("wms.query.cache." + name, this,
                    value -> value.stats().get(name).doubleValue()).register(registry);
        }
        io.micrometer.core.instrument.Gauge.builder("wms.query.cache.entries", local, Cache::estimatedSize).register(registry);
        io.micrometer.core.instrument.Gauge.builder("wms.query.cache.origin.active", origin,
                permits -> properties.originConcurrency() - permits.availablePermits()).register(registry);
    }

    public record Entry(long expiresAt, String value) { }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void close() { local.invalidateAll(); redis.close(); }
}
