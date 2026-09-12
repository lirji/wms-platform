package com.lrj.wms.inbound.receipt;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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

/** S8-04：入库 HTTP 建单/列表/收货 202，越仓拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboundHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inbound")
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
        registry.add("wms.inbound.datasource.url", MYSQL::getJdbcUrl);
        registry.add("wms.inbound.datasource.username", MYSQL::getUsername);
        registry.add("wms.inbound.datasource.password", MYSQL::getPassword);
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
    void createListReceiveAndRejectCrossWarehouse() throws Exception {
        String token = token(List.of("WH-A"));
        HttpResponse<String> created = post("/api/wms/v1/warehouses/WH-A/inbound-orders", token, "KEY-ASN-1",
                "{\"sourceSystem\":\"OMS\",\"externalNo\":\"EXT-HTTP-1\",\"ownerId\":\"OWNER-1\","
                        + "\"lines\":[{\"externalLineId\":\"LINE-1\",\"skuId\":\"SKU-STD\",\"expectedQty\":\"10\",\"unit\":\"EA\"}]}");
        assertEquals(201, created.statusCode());
        HttpResponse<String> duplicateLine = post("/api/wms/v1/warehouses/WH-A/inbound-orders", token, "KEY-ASN-LINE",
                "{\"sourceSystem\":\"OMS\",\"externalNo\":\"EXT-HTTP-LINE\",\"ownerId\":\"OWNER-1\","
                        + "\"lines\":[{\"externalLineId\":\"LINE-1\",\"skuId\":\"SKU-STD\",\"expectedQty\":\"2\",\"unit\":\"EA\"}]}");
        assertEquals(409, duplicateLine.statusCode());
        assertTrue(duplicateLine.body().contains("DUPLICATE_DOCUMENT"));
        HttpResponse<String> list = get("/api/wms/v1/warehouses/WH-A/inbound-orders", token);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("EXT-HTTP-1"));
        HttpResponse<String> receipt = post("/api/wms/v1/warehouses/WH-A/inbound-orders/KEY-ASN-1/receipts", token,
                "CMD-R1", "{\"lineId\":\"LINE-1\",\"qty\":\"4\",\"receiptPartId\":\"PART-1\"}");
        assertEquals(202, receipt.statusCode());
        assertTrue(receipt.body().contains("\"physicalStatus\":\"RECEIVED\""));
        assertTrue(receipt.body().contains("\"stockSyncStatus\":\"PENDING\""));
        HttpResponse<String> quality = post("/api/wms/v1/warehouses/WH-A/quality-inspections/INSP-HTTP-1/results", token,
                "CMD-Q1", "{\"lineId\":\"LINE-1\",\"acceptedQty\":\"4\",\"rejectedQty\":\"0\"}");
        assertEquals(200, quality.statusCode());
        HttpResponse<String> putaway = post("/api/wms/v1/warehouses/WH-A/tasks/TASK-HTTP-1/putaways", token, "CMD-P1",
                "{\"inboundOrderId\":\"KEY-ASN-1\",\"lineId\":\"LINE-1\",\"targetLocationId\":\"LOC-1\","
                        + "\"locationType\":\"STORAGE\",\"qty\":\"4\"}");
        assertEquals(202, putaway.statusCode());
        assertTrue(putaway.body().contains("\"physicalStatus\":\"PUTAWAY\""));
        HttpResponse<String> tasks = get("/api/wms/v1/warehouses/WH-A/tasks?taskType=PUTAWAY", token);
        assertEquals(200, tasks.statusCode());
        assertTrue(tasks.body().contains("TASK-HTTP-1"));
        HttpResponse<String> task = get("/api/wms/v1/warehouses/WH-A/tasks/TASK-HTTP-1", token);
        assertEquals(200, task.statusCode());
        HttpResponse<String> completedClaim = post("/api/wms/v1/warehouses/WH-A/tasks/TASK-HTTP-1/claims", token,
                "CMD-CLAIM-DONE", "{\"expectedVersion\":1}");
        assertEquals(409, completedClaim.statusCode());
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword()); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO inbound_task (id, enterprise_id, warehouse_id, task_type, document_id, "
                    + "document_line_id, planned_qty, completed_qty, state, assignee_id, claim_epoch, version, created_at, "
                    + "updated_at) VALUES ('TASK-READY-1', 'ENT-1', 'WH-A', 'PUTAWAY', 'KEY-ASN-1', 'LINE-1', 4, 0, "
                    + "'PLANNED', NULL, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        }
        HttpResponse<String> claimed = post("/api/wms/v1/warehouses/WH-A/tasks/TASK-READY-1/claims", token, "CMD-CLAIM-1",
                "{\"expectedVersion\":0}");
        assertEquals(200, claimed.statusCode());
        assertTrue(claimed.body().contains("\"claimEpoch\":1"));
        HttpResponse<String> wrongType = get("/api/wms/v1/warehouses/WH-A/tasks?taskType=PICK", token);
        assertEquals(400, wrongType.statusCode());
        HttpResponse<String> forbidden = get("/api/wms/v1/warehouses/WH-B/inbound-orders", token);
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

    private static String token(List<String> warehouses) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("wms-wh-a").issuer(ISSUER).audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000)).claim("enterprise_id", "ENT-1")
                .claim("warehouses", warehouses)
                .claim("scope", List.of("inbound.create", "inbound.read", "inbound.receive", "quality.inspect", "inbound.putaway", "task.read", "task.claim")).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }
}
