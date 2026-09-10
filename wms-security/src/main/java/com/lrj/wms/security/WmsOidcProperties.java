package com.lrj.wms.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OIDC 接入参数只来自环境；issuer 为空时不得回退免认证。 */
@ConfigurationProperties(prefix = "wms.oidc")
public class WmsOidcProperties {
    private String issuer = "";
    private String jwkSetUri = "";
    private String clientId = "";

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /** 是否启用资源服务器。 */
    public boolean enabled() {
        return issuer != null && !issuer.isBlank();
    }

    /** JWKS 地址；未单独配置时使用 Casdoor 惯例路径。 */
    public String resolveJwkSetUri() {
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return jwkSetUri;
        }
        return issuer.replaceAll("/+$", "") + "/.well-known/jwks";
    }
}
