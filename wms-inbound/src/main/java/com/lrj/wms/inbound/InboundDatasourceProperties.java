package com.lrj.wms.inbound;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 入库库连接，必须由环境显式提供。 */
@ConfigurationProperties(prefix = "wms.inbound.datasource")
public class InboundDatasourceProperties {
    private String url = "";
    private String username = "";
    private String password = "";

    public String url() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String password() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
