package com.lrj.wms.security;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本人只读提示单独认证；模式由真实后端决定，前端不能用Token权限兜底中央故障。 */
@RestController
final class WmsMeAccessController {
    private final WmsCentralAuthorization authorization;
    WmsMeAccessController(WmsCentralAuthorization authorization) { this.authorization = authorization; }

    @GetMapping("/api/wms/v1/me/access")
    ResponseEntity<?> access(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "warehouseId", required = false) String warehouse) {
        Object body = authorization != null ? authorization.navigation(jwt, warehouse) : Map.of(
                "mode", "LEGACY", "enterpriseId", WmsJwtAuthorities.enterpriseId(jwt),
                "warehouseIds", WmsJwtAuthorities.warehouses(jwt).stream().sorted().toList(),
                "scopes", WmsJwtAuthorities.operationScopes(jwt).stream().sorted().toList(),
                "observedAt", Instant.now().toString());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
