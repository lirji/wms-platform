package com.lrj.wms.inventory.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 库存库连接，必须由环境显式提供。 */
@ConfigurationProperties(prefix = "wms.inventory.datasource")
public class InventoryDatasourceProperties {
    private String url = "";
    private String username = "";
    private String password = "";

    /** 返回当前配置绑定的数据库 url，数据源组装不能另行猜测连接配置。 */
    public String url() {
        return url;
    }

    /** 由配置绑定写入数据库 url，避免不同组装路径出现多个配置来源。 */
    public void setUrl(String url) {
        this.url = url;
    }

    /** 返回当前配置绑定的数据库 username，数据源组装不能另行猜测连接配置。 */
    public String username() {
        return username;
    }

    /** 由配置绑定写入数据库 username，避免不同组装路径出现多个配置来源。 */
    public void setUsername(String username) {
        this.username = username;
    }

    /** 返回当前配置绑定的数据库 password，数据源组装不能另行猜测连接配置。 */
    public String password() {
        return password;
    }

    /** 由配置绑定写入数据库 password，避免不同组装路径出现多个配置来源。 */
    public void setPassword(String password) {
        this.password = password;
    }
}
