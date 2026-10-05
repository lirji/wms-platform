package com.lrj.wms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lrj.authz.protocol.GovernanceDtos.AccessContext;
import com.lrj.authz.protocol.ScopeAccessDtos.Plan;
import com.lrj.authz.protocol.ScopeDtos.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实HTTP响应检验消费边界；伪造旧声明、动作串权及故障都不能触达业务。 */
class WmsCentralAuthorizationTest {
    private HttpServer server;
    private WmsCentralAuthorization central;
    private final String tenant = UUID.randomUUID().toString();
    private final String principal = UUID.randomUUID().toString(), membership = UUID.randomUUID().toString();
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final AtomicInteger status = new AtomicInteger(200), requests = new AtomicInteger();
    private final AtomicBoolean allow = new AtomicBoolean(true), malformed = new AtomicBoolean();
    private final AtomicReference<Set<String>> warehouses = new AtomicReference<>(Set.of("WH-A"));
    private final AtomicReference<String> capturedCapability = new AtomicReference<>();
    private final AtomicReference<Set<String>> allowedCapabilities = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/governance/v1/access", exchange -> {
            requests.incrementAndGet();
            var request = json.readTree(exchange.getRequestBody());
            assertEquals("Bearer " + "s".repeat(48), exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("old-broad-token", exchange.getRequestHeaders().getFirst("X-User-Access-Token"));
            assertEquals(tenant, request.path("tenant_id").asText());
            String capability = request.path("capability").asText(), resource = request.path("resource_type").asText();
            capturedCapability.set(capability);
            var context = new AccessContext(principal, membership, 1, 1, 1,
                    tenant, "wms", "local", "local-wms", "HUMAN", UUID.randomUUID().toString());
            if (exchange.getRequestURI().getPath().endsWith("/navigation")) {
                var view = new com.lrj.authz.protocol.NavigationDtos.View("1", request.path("request_id").asText(), context,
                        1, central.bindings.contentHash(1), "a".repeat(64), Instant.now().toString(), "AVAILABLE",
                        List.of(new com.lrj.authz.protocol.NavigationDtos.Menu("wms.nav.group.in_out", null, null, "入出存", 1),
                                new com.lrj.authz.protocol.NavigationDtos.Menu("wms.nav.catalog", "wms.nav.group.in_out", "/catalog", "商品 / 库位", 2)),
                        List.of("wms.masterdata.read", "wms.masterdata.read.enterprise", "wms.masterdata.write.enterprise"));
                byte[] bytes = json.writeValueAsBytes(view); exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); return;
            }
            var clauses = List.of(new Clause("wms_warehouse".equals(resource) ? Kind.SPECIFIED_RESOURCES : Kind.TENANT_ALL,
                    "wms_warehouse".equals(resource) ? warehouses.get().stream().sorted().toList() : List.of(), false));
            boolean granted = allow.get() && (allowedCapabilities.get() == null || allowedCapabilities.get().contains(capability));
            var plan = new Plan("1", request.path("request_id").asText(), capability, resource,
                    granted ? "ALLOW" : "DENY", UUID.randomUUID().toString(), context,
                    UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), 1, 1, 1,
                    Instant.now().plusSeconds(25).toString(), granted ? List.of(new Alternative(UUID.randomUUID().toString(), 1, clauses)) : List.of());
            byte[] bytes = (malformed.get() ? "{}" : json.writeValueAsString(plan)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        central = new WmsCentralAuthorization(new WmsCentralSettings("http://127.0.0.1:" + server.getAddress().getPort(),
                "s".repeat(48), tenant, "ENT-DEMO", "wms", "local", "local-wms", "http://localhost:18090", "wms-central",
                Duration.ofMillis(200), Duration.ofMillis(500), 2));
    }
    @AfterEach void stop() { central.close(); server.stop(0); SecurityContextHolder.clearContext(); }

    private Jwt jwt(String enterprise) {
        // 旧令牌声称读写及A/B全仓，中央只授予A，所有后续决策必须忽略旧声明。
        return Jwt.withTokenValue("old-broad-token").header("alg", "RS256").subject("actor")
                .claim("tokenType", "access-token").claim("enterprise_id", enterprise).claim("warehouses", List.of("WH-A", "WH-B"))
                .claim("scope", String.join(" ", OperationScopeFilter.loadRules().stream().map(OperationScopeFilter.Rule::scope).distinct().toList())).build();
    }
    private MockHttpServletResponse request(String method, String path, String enterprise, AtomicBoolean called) throws Exception {
        var original = new JwtAuthenticationToken(jwt(enterprise));
        SecurityContextHolder.getContext().setAuthentication(original);
        var response = new MockHttpServletResponse();
        new OperationScopeFilter(central).doFilter(new MockHttpServletRequest(method, path), response, (req, res) -> {
            called.set(true);
            assertInstanceOf(WmsCentralJwt.class, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        });
        assertSame(original, SecurityContextHolder.getContext().getAuthentication());
        return response;
    }

    @Test void all94BindingsRecheckTheirExactActionAndDeniedOldClaimsCannotReachOwner() throws Exception {
        assertEquals(94, central.bindings.all().size());
        for (var binding : central.bindings.all()) {
            String path = binding.path().getPatternString().replace("{warehouseId}", "WH-A").replaceAll("\\{[^}]+}", "example");
            var called = new AtomicBoolean();
            allow.set(true); assertEquals(200, request(binding.method(), path, "ENT-DEMO", called).getStatus());
            assertTrue(called.get()); assertEquals(binding.capability(), capturedCapability.get());
            allow.set(false); called.set(false);
            assertEquals(403, request(binding.method(), path, "ENT-DEMO", called).getStatus()); assertFalse(called.get());
        }
    }
    @Test void narrowWriteCannotBorrowReadScopeOrOldWarehouseClaimAndRevocationIsImmediate() throws Exception {
        var called = new AtomicBoolean();
        String path = "/api/wms/v1/warehouses/WH-B/locations";
        warehouses.set(Set.of("WH-A", "WH-B"));
        assertEquals(200, request("GET", path, "ENT-DEMO", called).getStatus());
        warehouses.set(Set.of("WH-A")); called.set(false);
        assertEquals(403, request("POST", path, "ENT-DEMO", called).getStatus()); assertFalse(called.get());
        assertEquals("wms.masterdata.write", capturedCapability.get());
        called.set(false); assertEquals(200, request("POST", path.replace("WH-B", "WH-A"), "ENT-DEMO", called).getStatus());
        int before = requests.get(); allow.set(false); called.set(false);
        assertEquals(403, request("POST", path.replace("WH-B", "WH-A"), "ENT-DEMO", called).getStatus());
        assertEquals(before + 1, requests.get()); assertFalse(called.get());
    }
    @Test void foreignEnterpriseUnknownRoutesMalformedResponsesAndOutageFailClosed() throws Exception {
        var called = new AtomicBoolean();
        assertEquals(403, request("GET", "/api/wms/v1/skus", "OTHER", called).getStatus()); assertEquals(0, requests.get());
        assertEquals(403, request("POST", "/api/wms/v1/unknown", "ENT-DEMO", called).getStatus()); assertEquals(0, requests.get());
        malformed.set(true);
        assertEquals(503, request("GET", "/api/wms/v1/skus", "ENT-DEMO", called).getStatus());
        malformed.set(false); status.set(503);
        var outage = request("GET", "/api/wms/v1/skus", "ENT-DEMO", called);
        assertEquals(503, outage.getStatus()); assertTrue(outage.getContentAsString().contains("AUTHORIZATION_UNAVAILABLE"));
        status.set(401); assertEquals(401, request("GET", "/api/wms/v1/skus", "ENT-DEMO", called).getStatus()); assertFalse(called.get());
    }
    @Test void enterpriseCapabilityCannotBecomeAllWarehouseAndContextExpiresAtRequestEnd() {
        try (var authorized = central.authorize(jwt("ENT-DEMO"), central.bindings.route("POST", "/api/wms/v1/skus"))) {
            assertTrue(WmsJwtAuthorities.warehouses(authorized).isEmpty());
            WmsJwtAuthorities.requireWarehouse(authorized, "WH-A");
            assertEquals("wms.masterdata.write", capturedCapability.get());
            assertThrows(WarehouseForbiddenException.class, () -> WmsJwtAuthorities.requireWarehouse(authorized, "WH-B"));
            authorized.close();
            assertThrows(CentralAuthorizationException.class, () -> WmsJwtAuthorities.enterpriseId(authorized));
        }
    }
    @Test void catalogHashMatchesProductionAuthCanonicalPermissionManifest() {
        assertEquals("1a83b60d1cca7873695a07388c50f7202cd4236fe76d4fa0aeb594faddc44497", central.bindings.contentHash(1));
    }
    @Test void navigationChecksCurrentWarehouseAndPreservesEnterpriseCapabilityWithoutBorrowingWarehouseWrite() {
        allowedCapabilities.set(Set.of("wms.masterdata.read", "wms.masterdata.read.enterprise", "wms.masterdata.write.enterprise", "wms.transfer.create"));
        var view = central.navigation(jwt("ENT-DEMO"), "WH-B");
        assertEquals(List.of("WH-A"), view.warehouseIds());
        assertEquals(List.of("wms.masterdata.read.enterprise", "wms.masterdata.write.enterprise"), view.capabilities());
        assertTrue(view.menus().stream().anyMatch(menu -> "/catalog".equals(menu.route())));
        assertFalse(view.capabilities().contains("wms.masterdata.write"));
        assertFalse(view.capabilities().contains("wms.transfer.create"));
        assertEquals(50, requests.get());
        // transfer.create未出现在中央菜单提示里，仍须在其真实授予的A仓显示作业入口。
        var source = central.navigation(jwt("ENT-DEMO"), "WH-A");
        assertTrue(source.capabilities().contains("wms.transfer.create"));
        assertEquals(100, requests.get());
    }
}
