package com.lrj.wms.inventory.masterdata;

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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主数据只读 HTTP：JWT 仓范围生效；不依赖现场 Casdoor。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterdataHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                    .withUsername("wms").withPassword(UUID.randomUUID().toString());
            MYSQL.start();
            MysqlDataSource source = new MysqlDataSource();
            source.setUrl(MYSQL.getJdbcUrl());
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
    void missingTokenIsUnauthorized() throws Exception {
        assertEquals(401, get("/api/wms/v1/warehouses", null).statusCode());
    }

    @Test
    void warehouseATokenListsOnlyWarehouseA() throws Exception {
        HttpResponse<String> response = get("/api/wms/v1/warehouses", token(List.of("WH-A")));
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("WH-A"));
        assertTrue(!response.body().contains("WH-B") || warehouseCount(response.body()) == 1);
    }

    @Test
    void crossWarehouseLocationIsForbidden() throws Exception {
        HttpResponse<String> response = get("/api/wms/v1/warehouses/WH-B/locations", token(List.of("WH-A")));
        assertEquals(403, response.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(response.body(), "code"));
    }

    @Test
    void allowedWarehouseReturnsSeededLocationsAndSkus() throws Exception {
        HttpResponse<String> locations = get("/api/wms/v1/warehouses/WH-A/locations", token(List.of("WH-A")));
        assertEquals(200, locations.statusCode());
        assertTrue(locations.body().contains("WH-A-RCV"));
        HttpResponse<String> skus = get("/api/wms/v1/skus", token(List.of("WH-A")));
        assertEquals(200, skus.statusCode());
        assertTrue(skus.body().contains("SKU-NEAR"));
        assertTrue(skus.body().contains("SKU-EXPIRED"));
    }

    @Test
    void deniedTokenSeesNoWarehousesAndCannotReadLocations() throws Exception {
        String denied = token("wms-denied", List.of());
        HttpResponse<String> warehouses = get("/api/wms/v1/warehouses", denied);
        assertEquals(200, warehouses.statusCode());
        assertTrue(!warehouses.body().contains("WH-A"));
        assertTrue(!warehouses.body().contains("WH-B"));
        HttpResponse<String> locations = get("/api/wms/v1/warehouses/WH-A/locations", denied);
        assertEquals(403, locations.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(locations.body(), "code"));
    }

    @Test
    void opsCsvTokenSeesBothWarehouses() throws Exception {
        String ops = token("wms-ops", "WH-A,WH-B");
        HttpResponse<String> warehouses = get("/api/wms/v1/warehouses", ops);
        assertEquals(200, warehouses.statusCode());
        assertTrue(warehouses.body().contains("WH-A"));
        assertTrue(warehouses.body().contains("WH-B"));
        assertEquals(2, warehouseCount(warehouses.body()));
    }

    @Test
    void warehouseACannotReadWarehouseBLots() throws Exception {
        HttpResponse<String> response = get("/api/wms/v1/warehouses/WH-B/lots", token(List.of("WH-A")));
        assertEquals(403, response.statusCode());
        assertEquals("WAREHOUSE_FORBIDDEN", extract(response.body(), "code"));
    }

    @Test
    void warehouseALotsExposeExplicitExpiryInstants() throws Exception {
        HttpResponse<String> lots = get("/api/wms/v1/warehouses/WH-A/lots", token(List.of("WH-A")));
        assertEquals(200, lots.statusCode());
        assertTrue(lots.body().contains("LOT-NEAR"));
        assertTrue(lots.body().contains("LOT-EXP"));
        assertTrue(lots.body().contains("2026-09-17T13:00:00Z"));
        assertTrue(lots.body().contains("2026-09-09T13:00:00Z"));
        assertTrue(lots.body().contains("LOT-STD"));
        assertTrue(!lots.body().contains("2026-09-10T00:00:00Z"));
    }

    @Test
    void skuLotUnitsIncludeCasePackTwelveToOne() throws Exception {
        HttpResponse<String> units = get("/api/wms/v1/skus/SKU-LOT/units", token(List.of("WH-A")));
        assertEquals(200, units.statusCode());
        assertTrue(units.body().contains("CS"));
        assertTrue(units.body().contains("\"numerator\":\"12\""));
        assertTrue(units.body().contains("\"denominator\":\"1\""));
        HttpResponse<String> missing = get("/api/wms/v1/skus/SKU-MISSING/units", token(List.of("WH-A")));
        assertEquals(404, missing.statusCode());
        assertEquals("SKU_NOT_FOUND", extract(missing.body(), "code"));
    }

    @Test
    void writesAndReadsMasterdataById() throws Exception {
        String reader = token(List.of("WH-A"));
        String writer = token("wms-ops", List.of("WH-A", "WH-C"), List.of("masterdata.read", "masterdata.write"));
        HttpResponse<String> denied = post("/api/wms/v1/skus", reader, "KEY-SKU-DENY",
                "{\"code\":\"SKU-HTTP\",\"name\":\"接口商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":false,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-DENY\"}");
        assertEquals(403, denied.statusCode());
        assertEquals("SCOPE_FORBIDDEN", extract(denied.body(), "code"));
        HttpResponse<String> createdSku = post("/api/wms/v1/skus", writer, "KEY-SKU-HTTP",
                "{\"code\":\"SKU-HTTP\",\"name\":\"接口商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":true,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-HTTP\"}");
        assertEquals(201, createdSku.statusCode());
        assertTrue(createdSku.body().contains("SKU-HTTP"));
        HttpResponse<String> replaySku = post("/api/wms/v1/skus", writer, "KEY-SKU-HTTP",
                "{\"code\":\"SKU-HTTP\",\"name\":\"接口商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":true,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-HTTP\"}");
        assertEquals(201, replaySku.statusCode());
        HttpResponse<String> mismatch = post("/api/wms/v1/skus", writer, "KEY-SKU-HTTP",
                "{\"code\":\"SKU-OTHER\",\"name\":\"另一商品\",\"baseUnit\":\"EA\",\"quantityScale\":0,"
                        + "\"lotEnabled\":false,\"serialEnabled\":false,\"expiryEnabled\":false,"
                        + "\"clientOperationId\":\"KEY-SKU-HTTP\"}");
        assertEquals(409, mismatch.statusCode());
        HttpResponse<String> unit = post("/api/wms/v1/skus/SKU-HTTP/units", writer, "KEY-UNIT-HTTP",
                "{\"unitCode\":\"CS\",\"numerator\":\"12\",\"denominator\":\"1\",\"clientOperationId\":\"KEY-UNIT-HTTP\"}");
        assertEquals(201, unit.statusCode());
        HttpResponse<String> warehouse = post("/api/wms/v1/warehouses", writer, "KEY-WH-C",
                "{\"code\":\"WH-C\",\"name\":\"接口新仓\",\"timezone\":\"Asia/Shanghai\",\"clientOperationId\":\"KEY-WH-C\"}");
        assertEquals(201, warehouse.statusCode());
        HttpResponse<String> location = post("/api/wms/v1/warehouses/WH-A/locations", writer, "KEY-LOC-HTTP",
                "{\"code\":\"HTTP\",\"zoneCode\":\"A\",\"locationType\":\"STORAGE\",\"clientOperationId\":\"KEY-LOC-HTTP\"}");
        assertEquals(201, location.statusCode());
        HttpResponse<String> lot = post("/api/wms/v1/warehouses/WH-A/lots", writer, "KEY-LOT-HTTP",
                "{\"ownerId\":\"OWNER-SELF\",\"skuId\":\"SKU-HTTP\",\"lotCode\":\"LOT-HTTP\","
                        + "\"businessLotKey\":\"ENT-DEMO/OWNER-SELF/SKU-HTTP/LOT-HTTP\",\"clientOperationId\":\"KEY-LOT-HTTP\"}");
        assertEquals(201, lot.statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A", reader).statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/locations/WH-A-RCV", reader).statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/locations/WH-A-RCV/gate", reader).statusCode());
        HttpResponse<String> sku = get("/api/wms/v1/skus/SKU-HTTP", reader);
        assertEquals(200, sku.statusCode());
        assertTrue(sku.body().contains("CS"));
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/lots/LOT-HTTP", reader).statusCode());
        assertEquals(403, get("/api/wms/v1/warehouses/WH-B", reader).statusCode());
        assertEquals(404, get("/api/wms/v1/skus/SKU-MISSING", reader).statusCode());
        String auditReader = token("wms-ops", List.of("WH-A"), List.of("stock.audit", "operation.read", "task.read"));
        HttpResponse<String> ledger = get("/api/wms/v1/warehouses/WH-A/inventory/BAL-MISSING/ledger", auditReader);
        assertEquals(404, ledger.statusCode());
        assertEquals("BALANCE_NOT_FOUND", extract(ledger.body(), "code"));
        HttpResponse<String> operation = get("/api/wms/v1/operations/OP-MISSING", auditReader);
        assertEquals(404, operation.statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/action-effects", auditReader).statusCode());
        assertEquals(200, get("/api/wms/v1/warehouses/WH-A/tasks/TASK-MISSING/action-effects", auditReader).statusCode());
    }

    private HttpResponse<String> post(String path, String bearer, String key, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).header("Idempotency-Key", key)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessAndMetricsRequireRealConfigurationAndOperatorScope() throws Exception {
        assertEquals(200, get("/actuator/health/readiness", null).statusCode());
        assertEquals(403, get("/actuator/metrics", token(List.of("WH-A"))).statusCode());
        String operator = token("ops", List.of("WH-A"), List.of("observability.read"));
        assertEquals(200, get("/actuator/metrics", operator).statusCode());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/wms/v1/skus"))
                .header("Authorization", "Bearer " + token(List.of("WH-A")))
                .header("X-Request-Id", "review-r21-request").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("review-r21-request", response.headers().firstValue("X-Request-Id").orElseThrow());
        assertEquals(200, get("/actuator/metrics/http.server.requests", operator).statusCode());
        var missing = get("/api/wms/v1/skus/NO-SUCH-SKU", token(List.of("WH-A")));
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains(missing.headers().firstValue("X-Request-Id").orElseThrow()));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> warehouses) throws Exception {
        return token("wms-wh-a", warehouses);
    }

    private static String token(String subject, List<String> warehouses) throws Exception {
        return signed(subject, warehouses, List.of("masterdata.read"));
    }

    private static String token(String subject, List<String> warehouses, List<String> scopes) throws Exception {
        return signed(subject, warehouses, scopes);
    }

    private static String token(String subject, String warehousesCsv) throws Exception {
        return signed(subject, warehousesCsv, List.of("masterdata.read"));
    }

    private static String signed(String subject, Object warehouses, List<String> scopes) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("enterprise_id", SeedCatalog.ENTERPRISE)
                .claim("warehouses", warehouses)
                .claim("scope", scopes)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }

    private static int warehouseCount(String body) {
        int count = 0;
        if (body.contains("\"id\":\"WH-A\"") || body.contains("WH-A")) {
            count++;
        }
        if (body.contains("WH-B")) {
            count++;
        }
        return count;
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
