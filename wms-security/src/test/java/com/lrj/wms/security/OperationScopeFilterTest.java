package com.lrj.wms.security;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;

/** 遍历完整公开权限表，验证缺权/错误权限/组名碰撞不能触达 Controller，正确的签名 scope 可通过。 */
class OperationScopeFilterTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void allPublicOperationsRejectMissingOrWrongScopesAndAcceptTheirDeclaredScope() throws Exception {
        var rules = OperationScopeFilter.loadRules();
        assertFalse(rules.isEmpty());
        for (var rule : rules) {
            String path = rule.path().getPatternString().replaceAll("\\{[^}]+}", "example");
            assertRequest(rule.method(), path, Map.of(), false);
            assertRequest(rule.method(), path, Map.of("scope", "unrelated.read"), false);
            assertRequest(rule.method(), path, Map.of("groups", List.of("organization/" + rule.scope())), false);
            assertRequest(rule.method(), path, Map.of("scope", "unrelated.read " + rule.scope()), true);
            assertRequest(rule.method(), path, Map.of("permissions", List.of(rule.scope())), true);
        }
    }

    @Test void unregisteredOperationsFailClosedAndWarehouseClaimsCannotBecomeScopes() throws Exception {
        assertRequest("POST", "/api/wms/v1/new-admin-action", Map.of("scope", "masterdata.write"), false);
        assertRequest("GET", "/api/wms/v1/skus", Map.of("warehouses", List.of("masterdata.read")), false);
        assertRequest("HEAD", "/api/wms/v1/skus", Map.of("scope", "masterdata.read"), true);
        var jwt = jwt(Map.of("groups", List.of("masterdata.write")));
        assertThrows(ScopeForbiddenException.class, () -> WmsJwtAuthorities.requireScope(jwt, "masterdata.write"));
    }

    @Test void deniedScopeKeepsTheHttpCorrelationId() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(Map.of())));
        var request = new MockHttpServletRequest("POST", "/api/wms/v1/new-admin-action");
        request.addHeader("X-Request-Id", "permission-correlation");
        var response = new MockHttpServletResponse();
        new com.lrj.wms.runtime.observability.RequestCorrelationFilter().doFilter(request, response,
                (req, res) -> new OperationScopeFilter().doFilter(req, res, (ignoredRequest, ignoredResponse) -> fail("不应通过权限门禁")));
        assertEquals(403, response.getStatus());
        assertEquals("permission-correlation", response.getHeader("X-Request-Id"));
        assertTrue(response.getContentAsString().contains("\"requestId\":\"permission-correlation\""));
        assertNull(org.slf4j.MDC.get("requestId"));
    }

    private static void assertRequest(String method, String path, Map<String, Object> claims, boolean allowed) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt(claims)));
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new OperationScopeFilter().doFilter(new MockHttpServletRequest(method, path), response,
                (request, result) -> called.set(true));
        assertEquals(allowed, called.get(), method + " " + path + " " + claims);
        if (!allowed) assertEquals(403, response.getStatus());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("test").header("alg", "none").subject("actor")
                .claim("enterprise_id", "ENT").claims(value -> value.putAll(claims)).build();
    }
}
