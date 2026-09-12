package com.lrj.wms.runtime.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** 复用 BOM 管理的 HikariCP；凭据只进入连接配置，不输出日志。 */
public final class RuntimeDataSources {
    private RuntimeDataSources() { }

    /** 服务专用池；获取连接超时直接失败，不能悄悄回退为无池连接。 */
    public static HikariDataSource create(String name, String url, String user, String password, DatabaseBudget budget) {
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("当前持久化仅支持已验证的 MySQL JDBC URL");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("wms-" + name);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(budget.maximumPoolSize());
        config.setMinimumIdle(budget.minimumIdle());
        config.setConnectionTimeout(budget.connectionTimeoutMs());
        config.setValidationTimeout(budget.validationTimeoutMs());
        config.setInitializationFailTimeout(budget.connectionTimeoutMs());
        config.setMaxLifetime(600000);
        config.addDataSourceProperty("connectTimeout", budget.connectTimeoutMs());
        config.addDataSourceProperty("socketTimeout", budget.socketTimeoutMs());
        return new HikariDataSource(config);
    }
}
