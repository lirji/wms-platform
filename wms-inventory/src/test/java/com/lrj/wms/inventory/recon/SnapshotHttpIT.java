package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.SeedCatalog;
import com.lrj.wms.inventory.masterdata.SeedLocal;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
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
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S9-05：快照 HTTP 导出。测试 JWT，不依赖现场 Casdoor，不开放库账号。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SnapshotHttpIT {
    private static final String ISSUER = "http://localhost/test-issuer";
    private static final Instant RECEIVED = Instant.parse("2026-09-10T13:00:00Z");
    private static final MySQLContainer MYSQL;
    private static final KeyPair KEYS;

    static {
        try {
            MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                    .withUsername("wms").withPassword(UUID.randomUUID().toString());
            MYSQL.start();
            MysqlDataSource source = new MysqlDataSource();
            source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(MYSQL.getJdbcUrl(), "UTC"));
            source.setUser(MYSQL.getUsername());
            source.setPassword(MYSQL.getPassword());
            Clock clock = Clock.fixed(RECEIVED, ZoneOffset.UTC);
            SeedLocal.seed(source, clock, Set.of(SeedCatalog.WAREHOUSE_A, SeedCatalog.WAREHOUSE_B));
            Configuration config = new Configuration(new Environment("snap-http", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
            config.addMapper(MasterdataMapper.class);
            config.addMapper(InventoryMapper.class);
            config.addMapper(OutboxMapper.class);
            config.addMapper(CommandDedupMapper.class);
            try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(false)) {
                new InventoryApplicationService(session, clock).receive(SeedCatalog.ENTERPRISE, SeedCatalog.WAREHOUSE_A,
                        "OP-HTTP-SNAP", "DOC", "ACTOR",
                        StockBucketKey.of(SeedCatalog.ENTERPRISE, SeedCatalog.WAREHOUSE_A, SeedCatalog.OWNER,
                                SeedCatalog.WAREHOUSE_A + "-STO", "SKU-STD", MasterdataCodes.NO_LOT,
                                InventoryCodes.QUALITY_GOOD),
                        Quantity.parse("4", 0));
                session.commit();
            }
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
    void exportAndReadQuantitySnapshotThenRejectCrossWarehouse() throws Exception {
        assertEquals(401, post("/api/wms/v1/reconciliation-snapshots", null, "{}").statusCode());
        HttpResponse<String> created = post("/api/wms/v1/reconciliation-snapshots", token(List.of("WH-A")),
                "{\"warehouseIds\":[\"WH-A\"],\"cutoffId\":\"C-HTTP\",\"cutoff\":\"2026-09-12T10:00:00Z\","
                        + "\"sourceWatermark\":\"SRC-1\",\"postingWatermark\":\"POST-1\",\"receiptWatermark\":\"RCV-1\"}");
        assertEquals(202, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"state\":\"COMPLETE\""));
        String snapshotId = extract(created.body(), "snapshotJobId");
        HttpResponse<String> forbidden = get("/api/wms/v1/reconciliation-snapshots/" + snapshotId + "?warehouseId=WH-B",
                token(List.of("WH-A")));
        assertEquals(403, forbidden.statusCode());
        HttpResponse<String> read = get("/api/wms/v1/reconciliation-snapshots/" + snapshotId + "?warehouseId=WH-A",
                token(List.of("WH-A")));
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("quantity"), read.body());
        assertTrue(read.body().contains("EA"), read.body());
        assertTrue(read.body().contains("4"), read.body());
        assertFalse(read.body().contains("currency"), read.body());
        assertFalse(read.body().contains("amountMinor"), read.body());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> warehouses) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("wms-recon")
                .issuer(ISSUER)
                .audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
                .claim("enterprise_id", SeedCatalog.ENTERPRISE)
                .claim("warehouses", warehouses)
                .claim("scope", List.of("recon.export", "recon.read"))
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
