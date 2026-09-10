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
        return signed(subject, warehouses);
    }

    private static String token(String subject, String warehousesCsv) throws Exception {
        return signed(subject, warehousesCsv);
    }

    private static String signed(String subject, Object warehouses) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("enterprise_id", SeedCatalog.ENTERPRISE)
                .claim("warehouses", warehouses)
                .claim("scope", List.of("masterdata.read"))
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
