package com.lrj.wms.runtime.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 即时拒绝过载，不在请求线程上排无界队列；租户不能耗尽全部在途额度。 */
public final class AdmissionGate {
    private final AdmissionBudget budget;
    private final Bucket global;
    private final Cache<String, Bucket> tenants = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterAccess(Duration.ofMinutes(5)).build();

    public AdmissionGate(AdmissionBudget budget) {
        this.budget = budget;
        this.global = new Bucket(budget.globalConcurrency(), budget.globalRequestsPerSecond());
    }

    /** 返回许可证时必须在请求完成或异常后释放；被拒绝的请求不访问业务数据库。 */
    public Permit acquire(String tenant) {
        Bucket bucket = tenants.get(tenant, key -> new Bucket(budget.tenantConcurrency(), budget.tenantRequestsPerSecond()));
        if (!bucket.acquire()) return null;
        if (!global.acquire()) {
            bucket.concurrent.release();
            return null;
        }
        return new Permit(global.concurrent, bucket.concurrent);
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore global;
        private final Semaphore tenant;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit(Semaphore global, Semaphore tenant) { this.global = global; this.tenant = tenant; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) { global.release(); tenant.release(); }
        }
    }

    private static final class Bucket {
        private final Semaphore concurrent;
        private final int rate;
        private double tokens;
        private long refreshed = System.nanoTime();
        private Bucket(int concurrent, int rate) {
            this.concurrent = new Semaphore(concurrent); this.rate = rate; this.tokens = rate;
        }
        private synchronized boolean acquire() {
            long now = System.nanoTime();
            tokens = Math.min(rate, tokens + (now - refreshed) / 1_000_000_000d * rate);
            refreshed = now;
            if (tokens < 1 || !concurrent.tryAcquire()) return false;
            tokens--;
            return true;
        }
    }
}
