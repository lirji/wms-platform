package com.lrj.wms.runtime;

import com.lrj.wms.runtime.cache.QueryCacheProperties;
import com.lrj.wms.runtime.db.DatabaseBudget;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 配置示例必须能从真实环境变量命名绑定，不能默默使用另一个默认值。 */
class ConfigurationBindingTest {
    @Test void documentedEnvironmentNamesBindAndInvalidBudgetsFail() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                "WMS_RUNTIME_DB_MAXIMUMPOOLSIZE", "7", "WMS_RUNTIME_CACHE_REDISHOST", "cache.local",
                "WMS_RUNTIME_CACHE_REDISPORT", "16379", "WMS_RUNTIME_CACHE_REDISPASSWORD", "test-only")));
        var binder = Binder.get(environment);
        assertEquals(7, binder.bind("wms.runtime.db", DatabaseBudget.class).get().maximumPoolSize());
        var cache = binder.bind("wms.runtime.cache", QueryCacheProperties.class).get();
        assertEquals("cache.local", cache.redisHost());
        assertEquals(16379, cache.redisPort());
        assertFalse(cache.toString().contains("test-only"));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("invalid-systemEnvironment", Map.of("WMS_RUNTIME_DB_MAXIMUMPOOLSIZE", "0")));
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> Binder.get(environment).bind("wms.runtime.db", DatabaseBudget.class));
    }
}
