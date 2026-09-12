package com.lrj.wms.runtime.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 每实例数据库预算；不随动态配置变更，以免在途事务跨越两套连接语义。 */
@ConfigurationProperties("wms.runtime.db")
public record DatabaseBudget(@DefaultValue("16") int maximumPoolSize, @DefaultValue("2") int minimumIdle,
        @DefaultValue("1000") long connectionTimeoutMs, @DefaultValue("500") long validationTimeoutMs,
        @DefaultValue("5") int statementTimeoutSeconds, @DefaultValue("3000") int connectTimeoutMs,
        @DefaultValue("10000") int socketTimeoutMs) {
    public DatabaseBudget {
        if (maximumPoolSize < 1 || maximumPoolSize > 200 || minimumIdle < 0 || minimumIdle > maximumPoolSize
                || connectionTimeoutMs < 250 || connectionTimeoutMs > 30000
                || validationTimeoutMs < 250 || validationTimeoutMs > connectionTimeoutMs
                || statementTimeoutSeconds < 1 || statementTimeoutSeconds > 60
                || connectTimeoutMs < 250 || connectTimeoutMs > 30000
                || socketTimeoutMs < statementTimeoutSeconds * 1000L || socketTimeoutMs > 120000) {
            throw new IllegalArgumentException("数据库连接、查询与网络超时预算不合法");
        }
    }
}
