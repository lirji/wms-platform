package com.lrj.wms.security.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OIDC 接入参数只来自环境；issuer 为空时不得回退免认证。 */
@ConfigurationProperties(prefix = "wms.oidc")
public class WmsOidcProperties {
    private String issuer = "";
    private String jwkSetUri = "";
    private String clientId = "";

    /** 返回受治理的 issuer 配置，身份校验不能自行更换信任来源。 */
    public String getIssuer() {
        return issuer;
    }

    /** 由配置绑定提供 issuer，缺失时仍由原启用规则拒绝免认证回退。 */
    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    /** 返回显式 JWKS 配置，是否使用约定路径由统一解析入口决定。 */
    public String getJwkSetUri() {
        return jwkSetUri;
    }

    /** 由配置绑定提供 JWKS 地址，避免请求输入改变密钥来源。 */
    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    /** 返回本服务客户端标识，不能从调用方输入推断客户端身份。 */
    public String getClientId() {
        return clientId;
    }

    /** 由配置绑定提供客户端身份，避免运行中请求覆盖身份配置。 */
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
