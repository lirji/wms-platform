package com.lrj.wms.runtime.messaging.kafka;

import com.lrj.wms.runtime.observability.RuntimeDependencyCheck;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.health.contributor.Health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 使用真实broker请求而非本地进程状态；探针结果缓存5秒，避免无认证健康请求放大网络负载。 */
public final class KafkaDependencyHealth implements RuntimeDependencyCheck, AutoCloseable {
    private final AdminClient admin;
    private final BooleanSupplier workersReady;
    private long checkedAt;
    private Health cached = Health.outOfService().build();

    /** 显式接收 KafkaDependencyHealth 的协作对象或配置，保持本实例使用的依赖与创建入口一致。 */
    public KafkaDependencyHealth(KafkaSettings settings, BooleanSupplier workersReady) {
        var properties = settings.connection();
        properties.put("default.api.timeout.ms", "1000");
        properties.put("request.timeout.ms", "1000");
        admin = AdminClient.create(properties);
        this.workersReady = workersReady;
    }

    /** 基于本依赖的实际就绪条件返回健康状态，不能仅以线程存活代替可用。 */
    @Override
    public synchronized Health health() {
        long now = System.nanoTime();
        if (checkedAt != 0 && now - checkedAt < TimeUnit.SECONDS.toNanos(5)) return cached;
        try {
            var nodes =
                    admin.describeCluster(new DescribeClusterOptions().timeoutMs(1000))
                            .nodes()
                            .get(1500, TimeUnit.MILLISECONDS);
            cached =
                    !nodes.isEmpty() && workersReady.getAsBoolean()
                            ? Health.up().build()
                            : Health.down().build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cached = Health.down().build();
        } catch (Exception unavailable) {
            cached = Health.down().build();
        }
        checkedAt = System.nanoTime();
        return cached;
    }

    /** 释放本实例拥有的客户端或资源，避免重复创建后留下后台工作。 */
    @Override
    public void close() {
        admin.close(Duration.ofSeconds(2));
    }
}
