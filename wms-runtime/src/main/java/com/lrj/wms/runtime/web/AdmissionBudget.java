package com.lrj.wms.runtime.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 入站配额为单实例预算；扩容时必须合计全实例对数据库的负载。 */
@ConfigurationProperties("wms.runtime.admission")
public record AdmissionBudget(@DefaultValue("64") int globalConcurrency, @DefaultValue("16") int tenantConcurrency,
        @DefaultValue("200") int globalRequestsPerSecond, @DefaultValue("40") int tenantRequestsPerSecond) {
    public AdmissionBudget {
        if (globalConcurrency < 1 || globalConcurrency > 2000 || tenantConcurrency < 1
                || tenantConcurrency > globalConcurrency || globalRequestsPerSecond < 1
                || globalRequestsPerSecond > 100000 || tenantRequestsPerSecond < 1
                || tenantRequestsPerSecond > globalRequestsPerSecond) {
            throw new IllegalArgumentException("请求并发与租户配额不合法");
        }
    }
}
