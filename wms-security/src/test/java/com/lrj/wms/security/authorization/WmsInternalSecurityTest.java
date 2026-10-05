package com.lrj.wms.security.authorization;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 真实Spring过滤器和RSA/JWKS验签，证明内部机器与公开中央身份的信任边界互不扩大。 */
class WmsInternalSecurityTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/protocol-probe")
        String publicIdentity() {
            return "accepted";
        }

        @GetMapping("/internal/wms/v1/probe/{warehouseId}")
        String internal(@AuthenticationPrincipal Jwt jwt, @PathVariable String warehouseId) {
            // 测试Owner沿用生产边界，机器验签成功也不能代替企业、scope和仓校验。
            WmsJwtAuthorities.requireScope(jwt, "serial.registry.read");
            WmsJwtAuthorities.requireWarehouse(jwt, warehouseId);
            if (!"ENT-DEMO".equals(WmsJwtAuthorities.enterpriseId(jwt)))
                throw new ScopeForbiddenException("serial.registry.read");
            return "accepted";
        }

        @org.springframework.web.bind.annotation.ExceptionHandler({
            WarehouseForbiddenException.class,
            ScopeForbiddenException.class
        })
        org.springframework.http.ResponseEntity<?> denied() {
            return org.springframework.http.ResponseEntity.status(403).build();
        }
    }

    @Test
    void signedMachineIsInternalOnlyAndOwnerGuardsRemainEffective() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext(
                "/",
                exchange -> {
                    byte[] body =
                            new JWKSet(key.toPublicJWK())
                                    .toString()
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        jwks.start();
        String base = "http://127.0.0.1:" + jwks.getAddress().getPort();
        Path configuration = configuration(base);
        try (var context =
                new SpringApplicationBuilder(TestApplication.class)
                        .properties(settings(base, configuration, true))
                        .run()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String machine =
                    token(
                            key,
                            base + "/internal",
                            "wms-platform",
                            "inventory-worker",
                            "serial.registry.read",
                            "ENT-DEMO",
                            Instant.now().plusSeconds(120),
                            null);
            String human =
                    token(
                            key,
                            base + "/public",
                            "wms-central",
                            "reader-a",
                            "serial.registry.read",
                            "ENT-DEMO",
                            Instant.now().plusSeconds(120),
                            "access-token");
            assertEquals(200, get(port, "/internal/wms/v1/probe/WH-A", machine));
            assertEquals(401, get(port, "/protocol-probe", machine));
            assertEquals(401, get(port, "/api/wms/v1/warehouses", machine));
            assertEquals(200, get(port, "/protocol-probe", human));
            assertEquals(401, get(port, "/internal/wms/v1/probe/WH-A", human));
            assertEquals(
                    401,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    key,
                                    base + "/internal",
                                    "wms-platform",
                                    "old-human",
                                    "serial.registry.read",
                                    "ENT-DEMO",
                                    Instant.now().plusSeconds(120),
                                    null)));
            assertEquals(403, get(port, "/internal/wms/v1/probe/WH-B", machine));
            assertEquals(
                    403,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    key,
                                    base + "/internal",
                                    "wms-platform",
                                    "inventory-worker",
                                    "",
                                    "ENT-DEMO",
                                    Instant.now().plusSeconds(120),
                                    null)));
            assertEquals(
                    403,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    key,
                                    base + "/internal",
                                    "wms-platform",
                                    "inventory-worker",
                                    "serial.registry.read",
                                    "FOREIGN",
                                    Instant.now().plusSeconds(120),
                                    null)));
            assertEquals(
                    401,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    key,
                                    base + "/internal",
                                    "other-client",
                                    "inventory-worker",
                                    "serial.registry.read",
                                    "ENT-DEMO",
                                    Instant.now().plusSeconds(120),
                                    null)));
            assertEquals(
                    401,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    key,
                                    base + "/internal",
                                    "wms-platform",
                                    "inventory-worker",
                                    "serial.registry.read",
                                    "ENT-DEMO",
                                    Instant.now().minusSeconds(120),
                                    null)));
            RSAKey wrong = new RSAKeyGenerator(2048).keyID("test-key").generate();
            assertEquals(
                    401,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    wrong,
                                    base + "/internal",
                                    "wms-platform",
                                    "inventory-worker",
                                    "serial.registry.read",
                                    "ENT-DEMO",
                                    Instant.now().plusSeconds(120),
                                    null)));
            assertEquals(
                    401,
                    get(
                            port,
                            "/protocol-probe",
                            token(
                                    key,
                                    base + "/public",
                                    "wms-central",
                                    "reader-a",
                                    "",
                                    "ENT-DEMO",
                                    Instant.now().plusSeconds(120),
                                    "id-token")));
            assertEquals(
                    401,
                    get(
                            port,
                            "/internal/wms/v1/probe/WH-A",
                            token(
                                    key,
                                    base + "/internal",
                                    "wms-platform",
                                    "inventory-worker",
                                    "serial.registry.read",
                                    "ENT-DEMO",
                                    Instant.now().plusSeconds(120),
                                    "id-token")));
        } finally {
            jwks.stop(0);
        }
    }

    @Test
    void absentInternalConfigurationDeniesAndPartialConfigurationFailsStartup() throws Exception {
        String base = "http://127.0.0.1:18090";
        var settings = settings(base, configuration(base), false);
        try (var context =
                new SpringApplicationBuilder(TestApplication.class).properties(settings).run()) {
            assertEquals(
                    401,
                    get(
                            ((WebServerApplicationContext) context).getWebServer().getPort(),
                            "/internal/wms/v1/probe/WH-A",
                            null));
        }
        settings.put("wms.internal-oidc.issuer", base);
        assertThrows(
                Exception.class,
                () ->
                        new SpringApplicationBuilder(TestApplication.class)
                                .properties(settings)
                                .run());
    }

    @Test
    void malformedOrUnboundedSubjectConfigurationCannotBeEnabled() {
        assertFalse(new WmsInternalOidcProperties(null, null, null, null).configured());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new WmsInternalOidcProperties(
                                        "https://issuer",
                                        "client",
                                        "https://issuer/jwks",
                                        Set.of(" "))
                                .configured());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new WmsInternalOidcProperties(
                                        "https://user:password@issuer",
                                        "client",
                                        "https://issuer/jwks",
                                        Set.of("worker"))
                                .configured());
    }

    private Path configuration(String base) throws Exception {
        Path file = Files.createTempFile(directory, "central", ".properties");
        Files.writeString(
                file,
                "central.base-url=http://127.0.0.1:18545\ncentral.service-credential="
                        + "s".repeat(48)
                        + "\ncentral.tenant-id="
                        + UUID.randomUUID()
                        + "\ncentral.enterprise-id=ENT-DEMO\ncentral.application=wms"
                        + "\ncentral.environment=local\ncentral.organization=local-wms\ncentral.issuer="
                        + base
                        + "/public\ncentral.client-id=wms-central\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private Map<String, Object> settings(String base, Path file, boolean internal) {
        Map<String, Object> values =
                new java.util.HashMap<>(
                        Map.of(
                                "server.port",
                                0,
                                "spring.main.banner-mode",
                                "off",
                                "authz.client.enabled",
                                false,
                                "wms.iam.enabled",
                                true,
                                "wms.iam.configuration",
                                file.toString(),
                                "wms.oidc.issuer",
                                base + "/public",
                                "wms.oidc.client-id",
                                "wms-central",
                                "wms.oidc.jwk-set-uri",
                                base + "/jwks"));
        if (internal)
            values.putAll(
                    Map.of(
                            "wms.internal-oidc.issuer",
                            base + "/internal",
                            "wms.internal-oidc.client-id",
                            "wms-platform",
                            "wms.internal-oidc.jwk-set-uri",
                            base + "/jwks",
                            "wms.internal-oidc.allowed-subjects",
                            "inventory-worker"));
        return values;
    }

    private String token(
            RSAKey key,
            String issuer,
            String audience,
            String subject,
            String scope,
            String enterprise,
            Instant expiry,
            String purpose)
            throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(audience)
                        .subject(subject)
                        .issueTime(Date.from(Instant.now().minusSeconds(300)))
                        .expirationTime(Date.from(expiry))
                        .claim("scope", scope)
                        .claim("enterprise_id", enterprise)
                        .claim("warehouses", "WH-A");
        if (purpose != null) claims.claim("tokenType", purpose);
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private int get(int port, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient()
                .send(request.GET().build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
