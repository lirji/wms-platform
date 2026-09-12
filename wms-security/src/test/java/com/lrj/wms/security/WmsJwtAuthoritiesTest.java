package com.lrj.wms.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 仓/企业范围只信令牌声明。 */
class WmsJwtAuthoritiesTest {
    @Test
    void extractsEnterpriseWarehouseAndScopes() {
        Jwt jwt = jwt(Map.of(
                "enterprise_id", "ENT-DEMO",
                "warehouses", List.of("WH-A"),
                "scope", List.of("masterdata.read", "masterdata.write"),
                "groups", List.of("wms-platform/wms-ops")));
        List<String> authorities = WmsJwtAuthorities.authorities(jwt).stream().map(GrantedAuthority::getAuthority).toList();
        assertTrue(authorities.contains("ENTERPRISE_ENT-DEMO"));
        assertTrue(authorities.contains("WAREHOUSE_WH-A"));
        assertTrue(authorities.contains("masterdata.read"));
        assertTrue(authorities.contains("wms-ops"));
        assertEquals("ENT-DEMO", WmsJwtAuthorities.enterpriseId(jwt));
    }

    @Test
    void splitsCsvWarehouses() {
        Jwt jwt = jwt(Map.of("enterprise_id", "ENT-DEMO", "warehouses", "WH-A,WH-B"));
        assertEquals(2, WmsJwtAuthorities.warehouses(jwt).size());
        assertThrows(WarehouseForbiddenException.class, () -> WmsJwtAuthorities.requireWarehouse(jwt, "WH-C"));
    }

    @Test
    void requireScopeFailsClosedWhenMissing() {
        Jwt jwt = jwt(Map.of("enterprise_id", "ENT-DEMO", "warehouses", List.of("WH-A"), "scope", List.of("masterdata.read")));
        WmsJwtAuthorities.requireScope(jwt, "masterdata.read");
        assertThrows(ScopeForbiddenException.class, () -> WmsJwtAuthorities.requireScope(jwt, "masterdata.write"));
        Jwt empty = jwt(Map.of("enterprise_id", "ENT-DEMO", "warehouses", List.of("WH-A")));
        assertThrows(ScopeForbiddenException.class, () -> WmsJwtAuthorities.requireScope(empty, "masterdata.write"));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("wms-wh-a")
                .issuedAt(Instant.parse("2026-09-10T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-10T01:00:00Z")).claims(map -> map.putAll(claims)).build();
    }
}
