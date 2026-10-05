package com.lrj.wms.security.authorization;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Set;

/** 内部协议显式保留旧机器发行方，不能借机器兼容开放公开业务入口。 */
@ConfigurationProperties(prefix = "wms.internal-oidc")
public record WmsInternalOidcProperties(
        String issuer, String clientId, String jwkSetUri, Set<String> allowedSubjects) {
    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public WmsInternalOidcProperties {
        issuer = issuer == null ? "" : issuer;
        clientId = clientId == null ? "" : clientId;
        jwkSetUri = jwkSetUri == null ? "" : jwkSetUri;
        allowedSubjects = allowedSubjects == null ? Set.of() : Set.copyOf(allowedSubjects);
    }

    /** 全部缺失时拒绝内部入口；部分配置缺失或畸形时启动失败，避免意外扩大机器信任。 */
    boolean configured() {
        if (issuer.isEmpty()
                && clientId.isEmpty()
                && jwkSetUri.isEmpty()
                && allowedSubjects.isEmpty()) return false;
        if (!validUrl(issuer)
                || !validUrl(jwkSetUri)
                || clientId.isBlank()
                || clientId.length() > 200
                || !clientId.equals(clientId.strip())
                || allowedSubjects.isEmpty()
                || allowedSubjects.size() > 16
                || allowedSubjects.stream()
                        .anyMatch(value -> !value.matches("[A-Za-z0-9_:/.-]{1,100}"))) {
            throw new IllegalStateException("WMS内部机器身份配置不完整或无效");
        }
        return true;
    }

    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return value.equals(value.strip())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && uri.getQuery() == null
                    && Set.of("https", "http").contains(uri.getScheme());
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
