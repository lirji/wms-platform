package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.observability.RuntimeDependencyCheck;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.Health;

/** 使用真实broker请求而非本地进程状态；探针结果缓存5秒，避免无认证健康请求放大网络负载。 */
public final class KafkaDependencyHealth implements RuntimeDependencyCheck, AutoCloseable {
    private final AdminClient admin;
    private final BooleanSupplier workersReady;
    private long checkedAt;
    private Health cached = Health.outOfService().build();

    public KafkaDependencyHealth(KafkaSettings settings, BooleanSupplier workersReady) {
        var properties = settings.connection();
        properties.put("default.api.timeout.ms", "1000");
        properties.put("request.timeout.ms", "1000");
        admin = AdminClient.create(properties);
        this.workersReady = workersReady;
    }

    @Override public synchronized Health health() {
        long now = System.nanoTime();
        if (checkedAt != 0 && now - checkedAt < TimeUnit.SECONDS.toNanos(5)) return cached;
        try {
            var nodes = admin.describeCluster(new DescribeClusterOptions().timeoutMs(1000)).nodes().get(1500, TimeUnit.MILLISECONDS);
            cached = !nodes.isEmpty() && workersReady.getAsBoolean() ? Health.up().build() : Health.down().build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); cached = Health.down().build();
        } catch (Exception unavailable) { cached = Health.down().build(); }
        checkedAt = System.nanoTime();
        return cached;
    }

    @Override public void close() { admin.close(Duration.ofSeconds(2)); }
}
