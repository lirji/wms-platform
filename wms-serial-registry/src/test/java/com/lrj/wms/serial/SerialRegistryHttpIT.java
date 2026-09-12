package com.lrj.wms.serial;

import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 实际Spring运行配置、迁移、HTTP与RSA验签，拒绝普通用户冒用登记服务scope。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SerialRegistryHttpIT {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry");
    static final RSAKey RSA;
    static final com.sun.net.httpserver.HttpServer JWKS;
    static {
        try {
            MYSQL.start();
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            var pair = generator.generateKeyPair();
            RSA = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("serial-test").build();
            JWKS = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(RSA.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            JWKS.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            JWKS.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("wms.serial.datasource.url", MYSQL::getJdbcUrl);
        properties.add("wms.serial.datasource.username", MYSQL::getUsername);
        properties.add("wms.serial.datasource.password", MYSQL::getPassword);
        properties.add("wms.serial.access.allowed-subjects", () -> "inventory-worker");
        properties.add("wms.oidc.issuer", SerialRegistryHttpIT::issuer);
        properties.add("wms.oidc.jwk-set-uri", () -> issuer() + "/jwks");
        properties.add("wms.oidc.client-id", () -> "wms-platform");
    }

    @LocalServerPort int port;
    @Autowired DataSource source;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @AfterAll void cleanup() { JWKS.stop(0); MYSQL.stop(); }

    @Test void serviceIdentityScopeWarehouseIdempotencyAndAuditAreEnforcedOverHttp() throws Exception {
        String body = "{\"warehouseId\":\"WH-A\",\"skuId\":\"SKU\",\"serial\":\"SN-HTTP\",\"operationId\":\"RECEIPT-OP\"}";
        String token = token("inventory-worker", "ENT", List.of("WH-A"), List.of("serial.registry.write", "serial.registry.read"));
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base() + "/actuator/health/readiness")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, post("claims", token("ordinary-user", "ENT", List.of("WH-A"), List.of("serial.registry.write")), "CMD", body).statusCode());
        assertEquals(403, post("claims", token("inventory-worker", "ENT", List.of("WH-A"), List.of()), "CMD", body).statusCode());
        assertEquals(403, post("claims", token, "CMD", body.replace("WH-A", "WH-B")).statusCode());
        assertEquals(403, post("claims", token("inventory-worker", "OTHER-ENT", List.of("WH-A"), List.of("serial.registry.write")), "CMD", body).statusCode());
        assertEquals(400, post("claims", token, "CMD", "{}").statusCode());
        var claimed = post("claims", token, "CLAIM-CMD", body);
        assertEquals(200, claimed.statusCode(), claimed.body());
        assertEquals("CLAIMED", RuntimeMessage.JSON.readTree(claimed.body()).path("state").asString());
        assertEquals(200, post("claims", token, "CLAIM-CMD", body).statusCode());
        assertEquals(409, post("claims", token, "CLAIM-CMD", body.replace("SN-HTTP", "SN-OTHER")).statusCode());
        var active = post("activations", token, "ACTIVATE-CMD", body);
        assertEquals(200, active.statusCode(), active.body());
        assertEquals("ACTIVE", RuntimeMessage.JSON.readTree(active.body()).path("state").asString());
        assertEquals(200, post("activations", token, "ACTIVATE-CMD", body).statusCode());
        assertEquals(409, post("activations", token, "WRONG-OP", body.replace("RECEIPT-OP", "WRONG-OP")).statusCode());
        var jdbc = new JdbcTemplate(source);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM serial_registry", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM serial_http_command", Integer.class));
        assertEquals("inventory-worker", jdbc.queryForObject("SELECT actor_id FROM serial_http_command WHERE command_id='CLAIM-CMD'", String.class));
        assertEquals(200, get(token).statusCode());
        assertEquals(403, get(token("inventory-worker", "ENT", List.of("WH-B"), List.of("serial.registry.read"))).statusCode());
        assertEquals(403, get(token("inventory-worker", "OTHER-ENT", List.of("WH-A"), List.of("serial.registry.read"))).statusCode());
        // 审计完成写失败必须回滚认领，真实约束验证不能只测返回码。
        jdbc.execute("ALTER TABLE serial_http_command ADD CONSTRAINT reject_test_result CHECK (command_id <> 'ROLLBACK-CMD' OR result IS NULL)");
        assertEquals(503, post("claims", token, "ROLLBACK-CMD", body.replace("SN-HTTP", "SN-ROLLBACK")).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM serial_registry WHERE normalized_serial='SN-ROLLBACK'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM serial_http_command WHERE command_id='ROLLBACK-CMD'", Integer.class));
        jdbc.update("UPDATE serial_registry SET state='MISSING',version=version+1 WHERE normalized_serial='SN-HTTP'");
        var oldActivation = post("activations", token, "ACTIVATE-CMD", body);
        assertEquals(409, oldActivation.statusCode(), oldActivation.body());
        assertEquals("SERIAL_MISSING", RuntimeMessage.JSON.readTree(oldActivation.body()).path("code").asString());
        assertEquals("MISSING", jdbc.queryForObject("SELECT state FROM serial_registry WHERE normalized_serial='SN-HTTP'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM serial_http_command", Integer.class));
    }

    private HttpResponse<String> post(String action, String token, String key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/internal/wms/v1/serial-identities/" + action))
                .timeout(Duration.ofSeconds(5)).header("X-Wms-Enterprise-Id", "ENT").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/internal/wms/v1/serial-identities?skuId=SKU&serial=SN-HTTP"))
                .timeout(Duration.ofSeconds(5)).header("X-Wms-Enterprise-Id", "ENT").header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String base() { return "http://127.0.0.1:" + port; }
    private static String issuer() { return "http://127.0.0.1:" + JWKS.getAddress().getPort(); }
    private static String token(String subject, String enterprise, List<String> warehouses, List<String> scopes) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer()).audience("wms-platform").subject(subject)
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("enterprise_id", enterprise)
                .claim("warehouses", warehouses).claim("scope", scopes).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("serial-test").build(), claims);
        jwt.sign(new RSASSASigner(RSA)); return jwt.serialize();
    }
}
