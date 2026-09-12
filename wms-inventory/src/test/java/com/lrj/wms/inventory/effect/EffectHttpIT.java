package com.lrj.wms.inventory.effect;

import com.lrj.wms.inventory.masterdata.SeedCatalog;
import com.lrj.wms.inventory.masterdata.SeedLocal;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 效果身份 HTTP：同事实换键复用；未知/已过账不得新尝试。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EffectHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                    .withUsername("wms").withPassword(UUID.randomUUID().toString());
            MYSQL.start();
            MysqlDataSource source = new MysqlDataSource();
            source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(MYSQL.getJdbcUrl(), "UTC"), "UTC"));
            source.setUser(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            SeedLocal.seed(source, Clock.fixed(Instant.parse("2026-09-10T13:00:00Z"), ZoneOffset.UTC),
                    Set.of(SeedCatalog.WAREHOUSE_A, SeedCatalog.WAREHOUSE_B));
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @LocalServerPort
    private int port;

    @AfterAll
    static void cleanup() {
        MYSQL.stop();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("wms.inventory.datasource.url", MYSQL::getJdbcUrl);
        registry.add("wms.inventory.datasource.username", MYSQL::getUsername);
        registry.add("wms.inventory.datasource.password", MYSQL::getPassword);
        registry.add("wms.oidc.issuer", () -> ISSUER);
        registry.add("wms.oidc.client-id", () -> "wms-platform");
    }

    @TestConfiguration
    static class JwtOverride {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    @Test
    void nMinusOneClientOmitsDigestVersionAndIgnoresUnknownFields() throws Exception {
        String token = token(List.of("WH-A"));
        String body = """
                {"factType":"RECEIPT_PART","factParentId":"SES-N1","factPartId":"PART-N1","factLineId":"LINE-N1","action":"RECEIVE","unknownOptional":"ignore"}
                """;
        HttpResponse<String> first = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "compat-n1", body);
        assertEquals(201, first.statusCode());
        String effectId = extract(first.body(), "id");
        HttpResponse<String> withoutUnknown = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "compat-n1b",
                """
                {"factType":"RECEIPT_PART","factParentId":"SES-N1","factPartId":"PART-N1","factLineId":"LINE-N1","action":"RECEIVE"}
                """);
        assertEquals(201, withoutUnknown.statusCode());
        assertEquals(effectId, extract(withoutUnknown.body(), "id"));
        HttpResponse<String> attempt = post("/api/wms/v1/warehouses/WH-A/action-effects/" + effectId
                + "/execution-attempts", token, "compat-try-1",
                """
                {"expectedEffectVersion":0,"reason":"first","clientOperationId":"compat-try-1"}
                """);
        assertEquals(202, attempt.statusCode());
        assertEquals("ACCEPTED", extract(attempt.body(), "status"));
    }

    @Test
    void sameFactDifferentClientKeysReuseEffectId() throws Exception {
        String token = token(List.of("WH-A"));
        String body = """
                {"factType":"RECEIPT_PART","factParentId":"SES-1","factPartId":"PART-1","factLineId":"LINE-1","action":"RECEIVE"}
                """;
        HttpResponse<String> first = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "key-a", body);
        HttpResponse<String> second = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "key-b", body);
        assertEquals(201, first.statusCode());
        assertEquals(201, second.statusCode());
        String effectId = extract(first.body(), "id");
        assertEquals(effectId, extract(second.body(), "id"));
        assertTrue(first.body().contains("\"safeToRetry\":true"));
        HttpResponse<String> replay = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "key-a", body);
        assertEquals(201, replay.statusCode());
        assertEquals(effectId, extract(replay.body(), "id"));
        HttpResponse<String> mismatch = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "key-a",
                body.replace("PART-1", "PART-OTHER"));
        assertEquals(409, mismatch.statusCode());
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH", extract(mismatch.body(), "code"));
    }

    @Test
    void crossWarehouseRegisterIsForbidden() throws Exception {
        HttpResponse<String> response = post("/api/wms/v1/warehouses/WH-B/action-effects", token(List.of("WH-A")),
                "key-x",
                """
                {"factType":"RECEIPT_PART","factParentId":"SES-X","factPartId":"PART-X","factLineId":"LINE-X","action":"RECEIVE"}
                """);
        assertEquals(403, response.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(response.body(), "code"));
    }

    @Test
    void reauthorizationRespectsAttemptLifecycle() throws Exception {
        String token = token(List.of("WH-A"));
        String body = """
                {"factType":"SUB_ACTION","factParentId":"TASK-1","factPartId":"SUB-1","factLineId":"LINE-9","action":"PICK"}
                """;
        HttpResponse<String> registered = post("/api/wms/v1/warehouses/WH-A/action-effects", token, "pick-reg", body);
        String effectId = extract(registered.body(), "id");
        String attemptBody = """
                {"expectedEffectVersion":0,"reason":"first","clientOperationId":"pick-try-1"}
                """;
        HttpResponse<String> first = post("/api/wms/v1/warehouses/WH-A/action-effects/" + effectId + "/execution-attempts",
                token, "pick-try-1", attemptBody);
        assertEquals(202, first.statusCode());
        assertEquals("ACCEPTED", extract(first.body(), "status"));
        String commandId = extract(first.body(), "commandId");
        HttpResponse<String> duplicateOpen = post(
                "/api/wms/v1/warehouses/WH-A/action-effects/" + effectId + "/execution-attempts", token, "pick-try-2",
                """
                {"expectedEffectVersion":1,"reason":"again","clientOperationId":"pick-try-2"}
                """);
        assertEquals(409, duplicateOpen.statusCode());
        assertEquals("STALE_EXECUTION_ATTEMPT", extract(duplicateOpen.body(), "code"));
        jdbc().update("UPDATE stock_effect SET state='STARTED' WHERE id=?", effectId);
        HttpResponse<String> started = post(
                "/api/wms/v1/warehouses/WH-A/action-effects/" + effectId + "/execution-attempts", token, "pick-try-3",
                """
                {"expectedEffectVersion":1,"reason":"started","clientOperationId":"pick-try-3"}
                """);
        assertEquals(409, started.statusCode());
        jdbc().update("UPDATE stock_effect SET state='UNKNOWN' WHERE id=?", effectId);
        HttpResponse<String> unknown = post(
                "/api/wms/v1/warehouses/WH-A/action-effects/" + effectId + "/execution-attempts", token, "pick-try-4",
                """
                {"expectedEffectVersion":1,"reason":"unknown","clientOperationId":"pick-try-4"}
                """);
        assertEquals(202, unknown.statusCode());
        assertEquals("RECOVERY_PENDING", extract(unknown.body(), "status"));
        jdbc().update("UPDATE stock_effect SET state='SAFE_CLOSED' WHERE id=?", effectId);
        HttpResponse<String> next = post(
                "/api/wms/v1/warehouses/WH-A/action-effects/" + effectId + "/execution-attempts", token, "pick-try-5",
                "{\"expectedEffectVersion\":1,\"reason\":\"closed\",\"previousCommandId\":\"" + commandId
                        + "\",\"clientOperationId\":\"pick-try-5\"}");
        assertEquals(202, next.statusCode());
        assertNotEquals(commandId, extract(next.body(), "commandId"));
        jdbc().update("UPDATE stock_effect SET applied_command_id=active_command_id, state='APPLIED' WHERE id=?",
                effectId);
        HttpResponse<String> applied = post(
                "/api/wms/v1/warehouses/WH-A/action-effects/" + effectId + "/execution-attempts", token, "pick-try-6",
                """
                {"expectedEffectVersion":2,"reason":"applied","clientOperationId":"pick-try-6"}
                """);
        assertEquals(409, applied.statusCode());
        assertEquals("EFFECT_ALREADY_APPLIED", extract(applied.body(), "code"));
        HttpResponse<String> got = get("/api/wms/v1/warehouses/WH-A/action-effects/" + effectId, token);
        assertEquals(200, got.statusCode());
        assertTrue(got.body().contains("\"safeToRetry\":false"));
        assertFalse(got.body().contains("\"safeToRetry\":true"));
    }

    private JdbcTemplate jdbc() {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(MYSQL.getJdbcUrl(), "UTC"), "UTC"));
        source.setUser(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());
        return new JdbcTemplate(source);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET()
                .header("Authorization", "Bearer " + bearer).header("X-Request-Id", UUID.randomUUID().toString()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, String idempotency, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Idempotency-Key", idempotency)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> warehouses) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("wms-wh-a")
                .issuer(ISSUER)
                .audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("enterprise_id", SeedCatalog.ENTERPRISE)
                .claim("warehouses", warehouses)
                .claim("scope", List.of("task.read", "task.claim"))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    private static String extract(String body, String field) {
        String needle = "\"" + field + "\":\"";
        int start = body.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int from = start + needle.length();
        return body.substring(from, body.indexOf('"', from));
    }
}
