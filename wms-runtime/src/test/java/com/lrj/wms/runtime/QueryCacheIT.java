package com.lrj.wms.runtime;

import com.lrj.wms.runtime.cache.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis 验证跨实例命中、断连后回源有界、过期刷新与 key 隔离。 */
class QueryCacheIT {
    @Test void twoInstancesShareRedisButNotTenantDataAndRecoverFromFailure() throws Exception {
        try (var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redis.start();
            var config = new QueryCacheProperties(redis.getHost(), redis.getMappedPort(6379), "", false,
                    "test:cache:", 500, 50, 10, 1, 100);
            try (var first = new ReadQueryCache(config); var second = new ReadQueryCache(config)) {
                AtomicInteger reads = new AtomicInteger();
                assertEquals("A", first.read("tenant-A", () -> { reads.incrementAndGet(); return Map.of("name", "A"); }).get("name"));
                assertEquals("A", second.read("tenant-A", () -> { fail("应命中另一实例的 L2"); return Map.of(); }).get("name"));
                assertEquals("B", second.read("tenant-B", () -> Map.of("name", "B")).get("name"));
                assertEquals(1, second.stats().get("l2Hits").intValue());
                Thread.sleep(550);
                assertEquals("new", second.read("tenant-A", () -> Map.of("name", "new")).get("name"));
                redis.stop();
                assertEquals("fallback", first.read("cold", () -> Map.of("name", "fallback")).get("name"));
            }
        }
    }

    @Test void blockedOriginCannotCreateUnboundedDifferentKeyLoads() throws Exception {
        var config = new QueryCacheProperties("", 6379, "", false, "test:", 500, 50, 10, 1, 100);
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var cache = new ReadQueryCache(config); var executor = Executors.newFixedThreadPool(2)) {
            var request = executor.submit(() -> cache.read("one", () -> {
                loading.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
                return Map.of("ok", true);
            }));
            assertTrue(loading.await(2, TimeUnit.SECONDS));
            try { assertThrows(QueryCacheBusyException.class, () -> cache.read("two", () -> Map.of("unexpected", true))); }
            finally { release.countDown(); }
            assertEquals(true, request.get(2, TimeUnit.SECONDS).get("ok"));
            assertEquals(true, cache.read("two", () -> Map.of("ok", true)).get("ok"));
        }
    }
}
