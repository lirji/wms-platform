package com.lrj.wms.outbound.order;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S8-04：出库 HTTP 建单/列表，越仓拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboundHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound")
                    .withUsername("wms").withPassword(UUID.randomUUID().toString());
            MYSQL.start();
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
        registry.add("wms.outbound.datasource.url", MYSQL::getJdbcUrl);
        registry.add("wms.outbound.datasource.username", MYSQL::getUsername);
        registry.add("wms.outbound.datasource.password", MYSQL::getPassword);
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
    void createListAndRejectCrossWarehouse() throws Exception {
        String token = token(List.of("WH-A"));
        HttpResponse<String> created = post("/api/wms/v1/warehouses/WH-A/outbound-orders", token, "KEY-OB-1",
                "{\"allocationId\":\"ALLOC-1\",\"attemptId\":\"ATT-1\",\"ownerId\":\"OWNER-1\","
                        + "\"authorizationId\":\"AUTH-1\",\"lines\":[{\"orderLineId\":\"OL-1\",\"skuId\":\"SKU-STD\","
                        + "\"qty\":\"6\",\"baseUnit\":\"EA\"}]}");
        assertEquals(201, created.statusCode());
        String orderId = textBetween(created.body(), "\"id\":\"", "\"");
        HttpResponse<String> list = get("/api/wms/v1/warehouses/WH-A/outbound-orders", token);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("ALLOC-1"));
        HttpResponse<String> planned = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/pick-tasks",
                token, "CMD-PLAN-1", "{\"orderLineId\":\"OL-1\",\"sourceLocationId\":\"LOC-P\","
                        + "\"stagingLocationId\":\"LOC-S\",\"qty\":\"3\"}");
        assertEquals(201, planned.statusCode());
        String taskId = textBetween(planned.body(), "\"taskId\":\"", "\"");
        HttpResponse<String> picked = post("/api/wms/v1/warehouses/WH-A/tasks/" + taskId + "/picks", token, "CMD-PICK-1",
                "{\"qty\":\"2\"}");
        assertEquals(202, picked.statusCode());
        HttpResponse<String> packed = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/packings", token,
                "CMD-PACK-1", "{\"orderLineId\":\"OL-1\",\"qty\":\"2\"}");
        assertEquals(201, packed.statusCode());
        HttpResponse<String> shipped = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/shipments",
                token, "CMD-SHIP-1", "{\"orderLineId\":\"OL-1\",\"qty\":\"1\"}");
        assertEquals(202, shipped.statusCode());
        HttpResponse<String> cancelled = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/cancellations",
                token, "CMD-CXL-1", "{\"orderLineId\":\"OL-1\"}");
        assertEquals(202, cancelled.statusCode());
        HttpResponse<String> forbidden = get("/api/wms/v1/warehouses/WH-B/outbound-orders", token);
        assertEquals(403, forbidden.statusCode());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, String key, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).header("Idempotency-Key", key)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String textBetween(String body, String start, String end) {
        int from = body.indexOf(start);
        if (from < 0) {
            throw new AssertionError("缺少字段: " + start + " in " + body);
        }
        int begin = from + start.length();
        int to = body.indexOf(end, begin);
        return body.substring(begin, to);
    }

    private static String token(List<String> warehouses) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("wms-wh-a").issuer(ISSUER).audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000)).claim("enterprise_id", "ENT-1")
                .claim("warehouses", warehouses).claim("scope", List.of("outbound.create", "outbound.read")).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }
}
