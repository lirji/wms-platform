package com.lrj.wms.security;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/** 显式文件固定企业/中央组织映射；浏览器、Token或请求头不能覆盖部署绑定。 */
record WmsCentralSettings(String baseUrl, String credential, String tenant, String enterprise,
        String application, String environment, String organization, String issuer, String clientId,
        Duration connectTimeout, Duration timeout, int maximumConcurrent) {
    /** 0600普通文件、有限字段和OIDC绑定同时成立才允许启用中央模式。 */
    static WmsCentralSettings read(String filename, WmsOidcProperties oidc) {
        try {
            Path path = Path.of(filename);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8192
                    || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) {
                throw new IllegalArgumentException();
            }
            Properties values = new Properties();
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] pair = line.split("=", 2);
                if (pair.length != 2 || values.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalArgumentException();
            }
            var settings = new WmsCentralSettings(required(values, "central.base-url"), required(values, "central.service-credential"),
                    required(values, "central.tenant-id"), required(values, "central.enterprise-id"),
                    required(values, "central.application"), required(values, "central.environment"),
                    required(values, "central.organization"), required(values, "central.issuer"), required(values, "central.client-id"),
                    Duration.ofMillis(Long.parseLong(values.getProperty("central.connect-timeout-ms", "1000"))),
                    Duration.ofMillis(Long.parseLong(values.getProperty("central.timeout-ms", "5000"))),
                    Integer.parseInt(values.getProperty("central.maximum-concurrent", "8")));
            if (!UUID.fromString(settings.tenant()).toString().equals(settings.tenant())
                    || !"wms".equals(settings.application()) || !settings.enterprise().matches("[A-Za-z0-9_:/.-]{1,100}")
                    || !settings.organization().matches("[a-z0-9][a-z0-9-]{0,99}")
                    || !oidc.enabled() || !settings.issuer().equals(oidc.getIssuer())
                    || !settings.clientId().equals(oidc.getClientId())
                    || settings.maximumConcurrent() < 1 || settings.maximumConcurrent() > 16) {
                throw new IllegalArgumentException();
            }
            return settings;
        } catch (Exception failure) {
            // 不记录文件内容、路径中的机密或第三方错误，配置错误不能进入ready。
            throw new IllegalStateException("WMS中央权限配置无效或与OIDC绑定冲突");
        }
    }
    private static String required(Properties values, String name) {
        String value = values.getProperty(name);
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 512) {
            throw new IllegalArgumentException();
        }
        return value;
    }
    /** record默认输出包含服务凭据，统一禁止输出原值。 */
    @Override public String toString() { return "WmsCentralSettings[credentials=redacted]"; }
}
