package com.lrj.wms.runtime.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;

/** 有界 Redis 连接/命令队列。不可用时短暂打开熔断，回源仍受独立预算约束。 */
final class RedisQueryStore implements AutoCloseable {
    private final RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private volatile long retryAfter;
    private final java.util.concurrent.atomic.LongAdder errors = new java.util.concurrent.atomic.LongAdder();
    long errors() { return errors.sum(); }

    RedisQueryStore(QueryCacheProperties properties) {
        if (properties.redisHost().isBlank()) { client = null; return; }
        var timeout = Duration.ofMillis(properties.redisTimeoutMs());
        var uri = RedisURI.Builder.redis(properties.redisHost(), properties.redisPort()).withSsl(properties.redisTls())
                .withTimeout(timeout);
        if (!properties.redisPassword().isBlank()) uri.withPassword(properties.redisPassword().toCharArray());
        client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder().requestQueueSize(32)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(timeout).build())
                .timeoutOptions(TimeoutOptions.enabled(timeout)).build());
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection == null) connection = client.connect();
        return connection;
    }

    String get(String key) {
        if (client == null || System.nanoTime() < retryAfter) return null;
        try { return connection().sync().get(key); }
        catch (io.lettuce.core.RedisException error) { errors.increment(); retryAfter = System.nanoTime() + 1_000_000_000L; return null; }
    }

    void put(String key, String value, long ttl) {
        if (client == null || ttl <= 0 || System.nanoTime() < retryAfter) return;
        try { connection().sync().set(key, value, SetArgs.Builder.px(ttl)); }
        catch (io.lettuce.core.RedisException error) { errors.increment(); retryAfter = System.nanoTime() + 1_000_000_000L; }
    }

    @Override public synchronized void close() {
        if (connection != null) connection.close();
        if (client != null) client.shutdown();
    }
}
