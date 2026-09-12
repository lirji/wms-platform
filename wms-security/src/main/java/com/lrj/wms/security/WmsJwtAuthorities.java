package com.lrj.wms.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * 把 Casdoor JWT 的 groups/scope/warehouses/enterprise 归一成权限。
 * 企业与仓范围只信令牌，不接受请求头扩大权限。
 */
public final class WmsJwtAuthorities {
    public static final String WAREHOUSE_PREFIX = "WAREHOUSE_";
    public static final String ENTERPRISE_PREFIX = "ENTERPRISE_";

    private WmsJwtAuthorities() {
    }

    /** 供资源服务器使用的转换器。 */
    public static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(WmsJwtAuthorities::authorities);
        return converter;
    }

    /** 从令牌提取稳定权限编码。 */
    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String group : stringValues(jwt, "groups")) {
            int slash = group.lastIndexOf('/');
            authorities.add(new SimpleGrantedAuthority(slash >= 0 ? group.substring(slash + 1) : group));
        }
        for (String scope : operationScopes(jwt)) {
            authorities.add(new SimpleGrantedAuthority(scope));
        }
        String enterprise = firstNonBlank(jwt.getClaimAsString("enterprise_id"), jwt.getClaimAsString("owner"));
        if (enterprise != null) {
            authorities.add(new SimpleGrantedAuthority(ENTERPRISE_PREFIX + enterprise));
        }
        for (String warehouse : warehouses(jwt)) {
            authorities.add(new SimpleGrantedAuthority(WAREHOUSE_PREFIX + warehouse));
        }
        return authorities;
    }

    /** 令牌内仓范围。 */
    public static Set<String> warehouses(Jwt jwt) {
        Set<String> values = new LinkedHashSet<>(stringValues(jwt, "warehouses"));
        String csv = jwt.getClaimAsString("warehouses");
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    values.add(part.trim());
                }
            }
        }
        return values;
    }

    /** 企业标识，优先 enterprise_id，否则 owner。 */
    public static String enterpriseId(Jwt jwt) {
        String enterprise = firstNonBlank(jwt.getClaimAsString("enterprise_id"), jwt.getClaimAsString("owner"));
        if (enterprise == null || enterprise.isBlank()) {
            throw new IllegalArgumentException("令牌缺少企业范围");
        }
        return enterprise;
    }

    /** 校验调用仓必须在令牌仓列表中。 */
    public static void requireWarehouse(Jwt jwt, String warehouseId) {
        if (!warehouses(jwt).contains(warehouseId)) {
            throw new WarehouseForbiddenException(warehouseId);
        }
    }

    /** 校验令牌具备指定 scope；缺权失败关闭，不回落放行。 */
    public static void requireScope(Jwt jwt, String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("权限范围不能为空");
        }
        boolean allowed = operationScopes(jwt).contains(scope);
        if (!allowed) {
            throw new ScopeForbiddenException(scope);
        }
    }

    /** 操作授权只接受 scope/permissions；组名碰撞不能代替作业权限。 */
    public static Set<String> operationScopes(Jwt jwt) {
        Set<String> values = new LinkedHashSet<>();
        for (String claim : List.of("scope", "permissions")) {
            for (String text : stringValues(jwt, claim)) {
                for (String part : text.split("[\\s,]+")) if (!part.isBlank()) values.add(part);
            }
        }
        return values;
    }

    private static List<String> stringValues(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString());
                }
            }
        } else if (value instanceof String text && !text.isBlank() && !"warehouses".equals(claim)) {
            result.add(text);
        }
        return result;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
