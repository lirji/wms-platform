package com.lrj.wms.runtime;

import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.web.AdmissionBudget;
import com.lrj.wms.runtime.web.AdmissionGate;
import com.lrj.wms.runtime.web.RuntimeErrors;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 环境配置是当前唯一权威来源；非法预算启动失败，不默默套用默认值。 */
@AutoConfiguration
@EnableConfigurationProperties({com.lrj.wms.runtime.db.DatabaseTimePolicy.class, DatabaseBudget.class, AdmissionBudget.class, com.lrj.wms.runtime.cache.QueryCacheProperties.class})
public class RuntimeAutoConfiguration {
    @Bean public AdmissionGate admissionGate(AdmissionBudget budget) { return new AdmissionGate(budget); }
    @Bean(destroyMethod = "close")
    public com.lrj.wms.runtime.cache.ReadQueryCache readQueryCache(com.lrj.wms.runtime.cache.QueryCacheProperties properties) {
        return new com.lrj.wms.runtime.cache.ReadQueryCache(properties);
    }
    @Bean public RuntimeErrors runtimeErrors() { return new RuntimeErrors(); }
    @Bean
    public com.lrj.wms.runtime.observability.RuntimeReadiness wmsReadiness(
            org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> sources,
            org.springframework.core.env.Environment environment,
            org.springframework.beans.factory.ObjectProvider<com.lrj.wms.runtime.observability.RuntimeDependencyCheck> checks) {
        return new com.lrj.wms.runtime.observability.RuntimeReadiness(sources::getIfAvailable, environment,
                () -> checks.orderedStream().toList());
    }

    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<com.lrj.wms.runtime.observability.RequestCorrelationFilter> requestCorrelation() {
        var bean = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new com.lrj.wms.runtime.observability.RequestCorrelationFilter());
        bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
