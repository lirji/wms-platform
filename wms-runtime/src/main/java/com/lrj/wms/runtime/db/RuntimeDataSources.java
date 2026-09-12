package com.lrj.wms.runtime.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** 复用 BOM 管理的 HikariCP；凭据只进入连接配置，不输出日志。 */
public final class RuntimeDataSources {
    private RuntimeDataSources() { }

    /** 服务专用池；获取连接超时直接失败，不能悄悄回退为无池连接。 */
    public static HikariDataSource create(String name, String url, String user, String password, DatabaseBudget budget) {
        return create(name,url,user,password,budget,new DatabaseTimePolicy("UTC", ""));
    }

    /** 显式偏移与连接会话绑定；DATETIME在本项目承载瞬时字段，getObject统一返回Timestamp。 */
    public static HikariDataSource create(String name, String url, String user, String password, DatabaseBudget budget, DatabaseTimePolicy time) {
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("当前持久化仅支持已验证的 MySQL JDBC URL");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("wms-" + name);
        config.setJdbcUrl(withTimeZone(url, time.storageZone()));
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
    /** 非池化工具/隔离夹具也使用同一连接规则；URL中的矛盾配置直接拒绝，避免两套权威值。 */
    public static String withTimeZone(String url, String zone) {
        var offset = new DatabaseTimePolicy(zone, "").offset();
        if (url == null || !url.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("需要MySQL URL");
        var expected = java.util.Map.of("connectionTimeZone",offset,"preserveInstants","true",
                "forceConnectionTimeZoneToSession","true","treatMysqlDatetimeAsTimestamp","true",
                "sendFractionalSeconds","true","zeroDateTimeBehavior","EXCEPTION");
        int split = url.indexOf('?');
        var kept = new java.util.ArrayList<String>();
        if (split >= 0) for (String parameter : url.substring(split+1).split("&")) {
            var pair = parameter.split("=",2);
            String key = java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8);
            String canonical = "serverTimezone".equals(key) ? "connectionTimeZone" : key;
            if (expected.containsKey(canonical)) {
                String value = pair.length==2 ? java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8) : "";
                if ("connectionTimeZone".equals(canonical)) value = new DatabaseTimePolicy(value, "").offset();
                if (!expected.get(canonical).equalsIgnoreCase(value)) throw new IllegalArgumentException("JDBC URL与统一时间配置冲突");
            } else if (!parameter.isBlank()) kept.add(parameter);
        }
        expected.forEach((key,value) -> kept.add(key+"="+java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8)));
        return (split<0 ? url : url.substring(0,split))+"?"+String.join("&",kept);
    }

}
