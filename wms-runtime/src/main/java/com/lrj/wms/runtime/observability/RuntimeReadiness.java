package com.lrj.wms.runtime.observability;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;

/** 存活不代表能接业务：缺少鉴权配置、缺库、池耗尽或数据库失联均停止接流量。 */
public final class RuntimeReadiness implements HealthIndicator {
    private final Supplier<DataSource> dataSource;
    private final Environment environment;

    public RuntimeReadiness(Supplier<DataSource> dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public Health health() {
        if (environment.getProperty("wms.oidc.issuer", "").isBlank()
                || environment.getProperty("wms.oidc.client-id", "").isBlank()) {
            return Health.outOfService().withDetail("reason", "IDENTITY_NOT_CONFIGURED").build();
        }
        DataSource source = dataSource.get();
        if (source == null) return Health.outOfService().withDetail("reason", "DATABASE_NOT_CONFIGURED").build();
        try (var connection = source.getConnection()) {
            return connection.isValid(1) ? Health.up().build() : Health.down().build();
        } catch (Exception unavailable) {
            // 不把连接地址、账号或驱动异常暴露给无认证探针。
            return Health.down().withDetail("reason", "DATABASE_UNAVAILABLE").build();
        }
    }
}
