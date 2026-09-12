package com.lrj.wms.inventory.domain;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.masterdata.SeedCatalog;
import com.lrj.wms.inventory.masterdata.SeedLocal;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
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
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 移库、限制与独立调整 HTTP。不把盘点冻结当成 hold。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DomainHttpIT {
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

    @Autowired
    private SqlSessionFactory sessions;

    @Autowired
    private DataSource dataSource;

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
    void moveHoldAndAdjustmentUseRealStock() throws Exception {
        seedOnHand("10");
        String balanceId = balanceId("WH-A-STO");
        String writer = token(List.of("stock.move", "stock.hold", "stock.releaseHold", "adjustment.create",
                "adjustment.read", "adjustment.approve", "adjustment.apply"));
        String reader = token(List.of("stock.read"));
        HttpResponse<String> forbidden = post("/api/wms/v1/warehouses/WH-A/moves", reader, "KEY-MOVE-DENY",
                moveJson(balanceId, "KEY-MOVE-DENY"));
        assertEquals(403, forbidden.statusCode());
        HttpResponse<String> moved = post("/api/wms/v1/warehouses/WH-A/moves", writer, "KEY-MOVE-1",
                moveJson(balanceId, "KEY-MOVE-1"));
        assertEquals(202, moved.statusCode());
        assertTrue(moved.body().contains("\"physicalStatus\":\"MOVED\""));
        HttpResponse<String> replay = post("/api/wms/v1/warehouses/WH-A/moves", writer, "KEY-MOVE-1",
                moveJson(balanceId, "KEY-MOVE-1"));
        assertEquals(202, replay.statusCode());
        String stoAfterMove = new JdbcTemplate(dataSource).queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE id=?", String.class, balanceId);
        assertEquals("7.000000", new java.math.BigDecimal(stoAfterMove).setScale(6).toPlainString());
        HttpResponse<String> held = post("/api/wms/v1/warehouses/WH-A/stock-holds", writer, "KEY-HOLD-1",
                "{\"scope\":{\"balanceId\":\"" + balanceId + "\",\"qty\":\"4\"},\"reason\":\"RECALL\","
                        + "\"clientOperationId\":\"KEY-HOLD-1\"}");
        assertEquals(202, held.statusCode());
        String holdId = textBetween(held.body(), "\"holdId\":\"", "\"");
        HttpResponse<String> blocked = post("/api/wms/v1/warehouses/WH-A/moves", writer, "KEY-MOVE-2",
                "{\"sourceBalanceId\":\"" + balanceId + "\",\"targetLocationId\":\"WH-A-STG\",\"qty\":\"4\","
                        + "\"unit\":\"EA\",\"reason\":\"OVERFLOW\",\"clientOperationId\":\"KEY-MOVE-2\"}");
        assertEquals(409, blocked.statusCode());
        HttpResponse<String> released = post("/api/wms/v1/warehouses/WH-A/stock-holds/" + holdId + "/releases", writer,
                "KEY-REL-1", "{\"expectedVersion\":0,\"reason\":\"CLEAR\",\"clientOperationId\":\"KEY-REL-1\"}");
        assertEquals(200, released.statusCode());
        assertTrue(released.body().contains("RELEASED"));
        HttpResponse<String> created = post("/api/wms/v1/warehouses/WH-A/adjustments", writer, "KEY-ADJ-1",
                "{\"balanceId\":\"" + balanceId + "\",\"deltaQty\":\"-1\",\"reason\":\"DAMAGE\","
                        + "\"clientOperationId\":\"KEY-ADJ-1\"}");
        assertEquals(201, created.statusCode());
        String adjustmentId = textBetween(created.body(), "\"id\":\"", "\"");
        HttpResponse<String> early = post("/api/wms/v1/warehouses/WH-A/adjustments/" + adjustmentId + "/applications",
                writer, "KEY-ADJ-APPLY-EARLY",
                "{\"expectedVersion\":0,\"clientOperationId\":\"KEY-ADJ-APPLY-EARLY\"}");
        assertEquals(409, early.statusCode());
        HttpResponse<String> approved = post("/api/wms/v1/warehouses/WH-A/adjustments/" + adjustmentId + "/approvals",
                writer, "KEY-ADJ-APPR",
                "{\"decision\":\"APPROVED\",\"expectedVersion\":0,\"clientOperationId\":\"KEY-ADJ-APPR\"}");
        assertEquals(200, approved.statusCode());
        HttpResponse<String> applied = post("/api/wms/v1/warehouses/WH-A/adjustments/" + adjustmentId + "/applications",
                writer, "KEY-ADJ-APPLY", "{\"expectedVersion\":1,\"clientOperationId\":\"KEY-ADJ-APPLY\"}");
        assertEquals(202, applied.statusCode());
        HttpResponse<String> listed = get("/api/wms/v1/warehouses/WH-A/adjustments", writer);
        assertEquals(200, listed.statusCode());
        assertTrue(listed.body().contains(adjustmentId));
        HttpResponse<String> got = get("/api/wms/v1/warehouses/WH-A/adjustments/" + adjustmentId, writer);
        assertEquals(200, got.statusCode());
        assertTrue(got.body().contains("APPLIED"));
    }

    private void seedOnHand(String qty) {
        try (SqlSession session = sessions.openSession(false)) {
            new InventoryApplicationService(session, Clock.systemUTC()).receive(SeedCatalog.ENTERPRISE,
                    SeedCatalog.WAREHOUSE_A, "OP-SEED-DOMAIN", "DOC-SEED-DOMAIN", "tester",
                    StockBucketKey.of(SeedCatalog.ENTERPRISE, SeedCatalog.WAREHOUSE_A, SeedCatalog.OWNER, "WH-A-STO",
                            "SKU-STD", MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD),
                    Quantity.of(new java.math.BigDecimal(qty), 0));
            session.commit();
        }
    }

    private String balanceId(String locationId) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT id FROM stock_balance WHERE warehouse_id='WH-A' AND location_id=? AND sku_id='SKU-STD'",
                String.class, locationId);
    }

    private static String moveJson(String balanceId, String key) {
        return "{\"sourceBalanceId\":\"" + balanceId + "\",\"targetLocationId\":\"WH-A-STG\",\"qty\":\"3\","
                + "\"unit\":\"EA\",\"reason\":\"REBALANCE\",\"clientOperationId\":\"" + key + "\"}";
    }

    private HttpResponse<String> post(String path, String bearer, String key, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).header("Idempotency-Key", key)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> scopes) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("wms-ops").issuer(ISSUER).audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("enterprise_id", SeedCatalog.ENTERPRISE).claim("warehouses", List.of("WH-A"))
                .claim("scope", scopes).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
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
}
