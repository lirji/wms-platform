package com.lrj.wms.fulfillment;

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

/** S8-04：履约/调拨 HTTP 建单与查询。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment")
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
        registry.add("wms.fulfillment.datasource.url", MYSQL::getJdbcUrl);
        registry.add("wms.fulfillment.datasource.username", MYSQL::getUsername);
        registry.add("wms.fulfillment.datasource.password", MYSQL::getPassword);
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
    void createFulfillmentAndTransfer() throws Exception {
        String token = token(List.of("WH-A", "WH-B"));
        HttpResponse<String> fulfillment = post("/api/wms/v1/fulfillments", token, "KEY-FF-1",
                "{\"sourceSystem\":\"OMS\",\"sourceOrderNo\":\"SO-HTTP-1\",\"strategyVersion\":1,"
                        + "\"lines\":[{\"sourceLineId\":\"SL-1\",\"skuId\":\"SKU-STD\",\"requestedQty\":\"3\","
                        + "\"baseUnit\":\"EA\"}]}");
        assertEquals(201, fulfillment.statusCode());
        HttpResponse<String> listed = get("/api/wms/v1/fulfillments", token);
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains("SO-HTTP-1"));
        HttpResponse<String> transfer = post("/api/wms/v1/transfers", token, "TR-HTTP-1",
                "{\"sourceWarehouseId\":\"WH-A\",\"targetWarehouseId\":\"WH-B\","
                        + "\"lines\":[{\"lineId\":\"TL-1\",\"skuId\":\"SKU-STD\",\"plannedQty\":\"2\",\"unit\":\"EA\"}]}");
        assertEquals(201, transfer.statusCode());
        HttpResponse<String> got = get("/api/wms/v1/transfers/TR-HTTP-1?warehouseId=WH-A", token);
        assertEquals(200, got.statusCode());
        assertTrue(got.body().contains("WH-B"));
        String fulfillmentId = textBetween(fulfillment.body(), "\"id\":\"", "\"");
        HttpResponse<String> attempt = post("/api/wms/v1/fulfillments/" + fulfillmentId + "/attempts", token, "KEY-ATT-1",
                "{\"warehouses\":[\"WH-A\"],\"lines\":[{\"warehouseId\":\"WH-A\",\"orderLineId\":\"SL-1\","
                        + "\"skuId\":\"SKU-STD\",\"qty\":\"3\",\"baseUnit\":\"EA\"}]}");
        assertEquals(201, attempt.statusCode());
        HttpResponse<String> issued = post("/api/wms/v1/transfers/TR-HTTP-1/issues", token, "CMD-ISSUE-1",
                "{\"lineId\":\"TL-1\",\"qty\":\"2\"}");
        assertEquals(202, issued.statusCode());
        HttpResponse<String> authorized = post("/api/wms/v1/transfers/TR-HTTP-1/receipt-authorizations", token,
                "CMD-AUTH-1", "{\"lineId\":\"TL-1\",\"quantity\":\"2\"}");
        assertEquals(201, authorized.statusCode());
        String authorizationId = textBetween(authorized.body(), "\"authorizationId\":\"", "\"");
        String tokenVersion = textBetween(authorized.body(), "\"tokenVersion\":", ",");
        HttpResponse<String> received = post("/api/wms/v1/warehouses/WH-B/transfer-receipts", token, "CMD-RCV-1",
                "{\"transferId\":\"TR-HTTP-1\",\"lineId\":\"TL-1\",\"authorizationId\":\"" + authorizationId
                        + "\",\"tokenVersion\":" + tokenVersion + ",\"qty\":\"2\"}");
        assertEquals(202, received.statusCode());
        String cancelToken = token(List.of("WH-A", "WH-B"), List.of("fulfillment.create", "fulfillment.cancel", "fulfillment.read"));
        HttpResponse<String> denied = post("/api/wms/v1/fulfillments/" + fulfillmentId + "/cancellations", token,
                "KEY-CXL-DENY", "{\"expectedVersion\":1}");
        assertEquals(403, denied.statusCode());
        HttpResponse<String> cancelled = post("/api/wms/v1/fulfillments/" + fulfillmentId + "/cancellations", cancelToken,
                "KEY-CXL-1", "{\"expectedVersion\":1,\"reason\":\"OMS_ABORT\"}");
        assertEquals(202, cancelled.statusCode());
        assertTrue(cancelled.body().contains("CANCEL_REQUESTED"));
        HttpResponse<String> replay = post("/api/wms/v1/fulfillments/" + fulfillmentId + "/cancellations", cancelToken,
                "KEY-CXL-1", "{\"expectedVersion\":1,\"reason\":\"OMS_ABORT\"}");
        assertEquals(202, replay.statusCode());
        HttpResponse<String> after = get("/api/wms/v1/fulfillments/" + fulfillmentId, cancelToken);
        assertEquals(200, after.statusCode());
        assertTrue(after.body().contains("\"cancelRequested\":true"));
        assertTrue(!after.body().contains("ALLOCATED"));
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

    /** 不传 warehouseId 也必须校验参与仓；SQL 先授权再分页，不能由第一页无权行挤掉可见结果。 */
    @Test
    void transferQueriesRequireAnAuthorizedLegEvenWithoutWarehouseParameter() throws Exception {
        String admin = token(List.of("WH-A", "WH-B", "WH-C", "WH-D"));
        for (String[] row : List.of(new String[]{"ACCESS-A", "WH-A", "WH-B"},
                new String[]{"ACCESS-B", "WH-B", "WH-A"}, new String[]{"ACCESS-Z", "WH-C", "WH-D"})) {
            String body = "{\"sourceWarehouseId\":\"" + row[1] + "\",\"targetWarehouseId\":\"" + row[2]
                    + "\",\"lines\":[{\"lineId\":\"" + row[0] + "-L\",\"skuId\":\"SKU\",\"plannedQty\":\"1\"}]}";
            assertEquals(201, post("/api/wms/v1/transfers", admin, row[0], body).statusCode());
        }
        String whA = token(List.of("WH-A"));
        assertEquals(403, get("/api/wms/v1/transfers/ACCESS-Z", whA).statusCode());
        assertEquals(200, get("/api/wms/v1/transfers/ACCESS-A", whA).statusCode());
        assertEquals(200, get("/api/wms/v1/transfers/ACCESS-B", whA).statusCode());
        assertEquals(403, get("/api/wms/v1/transfers/ACCESS-A?warehouseId=WH-C", admin).statusCode());
        String cursor = null;
        var seen = new java.util.HashSet<String>();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        do {
            var response = get("/api/wms/v1/transfers?limit=1" + (cursor == null ? "" : "&cursor=" + cursor), whA);
            assertEquals(200, response.statusCode(), response.body());
            var page = json.readTree(response.body());
            for (var item : page.path("items")) assertTrue(seen.add(item.path("id").asString()));
            cursor = page.path("nextCursor").isTextual() ? page.path("nextCursor").asString() : null;
        } while (cursor != null);
        assertTrue(seen.containsAll(List.of("ACCESS-A", "ACCESS-B")));
        assertTrue(!seen.contains("ACCESS-Z"));
        assertEquals(403, get("/api/wms/v1/transfers", token(List.of("WH-A"), List.of("fulfillment.read"))).statusCode());
        var empty = json.readTree(get("/api/wms/v1/transfers", token(List.of())).body());
        assertEquals(0, empty.path("items").size());
    }

    private static String token(List<String> warehouses) throws Exception {
        return token(warehouses, List.of("fulfillment.create", "fulfillment.read", "fulfillment.execute", "transfer.create", "transfer.read", "transfer.authorizeReceipt", "transfer.receive"));
    }

    private static String token(List<String> warehouses, List<String> scopes) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("wms-ops").issuer(ISSUER).audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000)).claim("enterprise_id", "ENT-1")
                .claim("warehouses", warehouses).claim("scope", scopes).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }
}
