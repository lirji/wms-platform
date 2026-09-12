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
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM serial_registry WHERE normalized_serial='SN-HTTP'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM serial_http_command WHERE command_id IN ('CLAIM-CMD','ACTIVATE-CMD')", Integer.class));
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
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM serial_http_command WHERE command_id IN ('CLAIM-CMD','ACTIVATE-CMD')", Integer.class));
    }

    @Test void transferAndFoundRecoveryPreserveWarehouseEpochAndOriginalFacts() throws Exception {
        String both = token("inventory-worker", "ENT", List.of("WH-A", "WH-B"), List.of("serial.registry.write", "serial.registry.read"));
        String sourceOnly = token("inventory-worker", "ENT", List.of("WH-A"), List.of("serial.registry.write"));
        String destinationOnly = token("inventory-worker", "ENT", List.of("WH-B"), List.of("serial.registry.write", "serial.registry.read"));
        var identity = new LinkedHashMap<String,Object>(Map.of("warehouseId","WH-A","skuId","SKU","serial","sn-transfer-http","operationId","TRANSFER-RECEIPT"));
        ok("claims",both,"TX-CLAIM",identity,"CLAIMED");
        long epoch = ok("activations",both,"TX-ACTIVATE",identity,"ACTIVE").path("ownerEpoch").asLong();
        var prepare = new LinkedHashMap<String,Object>(identity);
        prepare.put("targetWarehouseId","WH-B"); prepare.put("transferId","TX-HTTP"); prepare.put("expectedEpoch",epoch);
        prepare.put("operationId","PREPARE-HTTP");
        assertEquals(403,post("transfer-preparations",sourceOnly,"TX-NO-TARGET",RuntimeMessage.JSON.writeValueAsString(prepare)).statusCode());
        ok("transfer-preparations",both,"TX-PREPARE",prepare,"TRANSFER_PREPARED");
        prepare.put("expectedEpoch",epoch+1);
        assertEquals(409,post("transfer-preparations",both,"TX-OLD-PREPARE",RuntimeMessage.JSON.writeValueAsString(prepare)).statusCode());
        var receive = new LinkedHashMap<String,Object>(Map.of("warehouseId","WH-B","skuId","SKU","serial","SN-TRANSFER-HTTP",
                "transferId","TX-HTTP","factRef","DEST-RECEIPT","expectedEpoch",epoch));
        assertEquals(409,post("destination-receivings",destinationOnly,"TX-EARLY",RuntimeMessage.JSON.writeValueAsString(receive)).statusCode());
        var release = new LinkedHashMap<String,Object>(receive); release.put("factRef","SOURCE-RELEASE");
        assertEquals(403,post("source-releases",destinationOnly,"TX-FORGED-SOURCE",RuntimeMessage.JSON.writeValueAsString(release)).statusCode());
        release.put("warehouseId","WH-A");
        ok("source-releases",sourceOnly,"TX-RELEASE",release,"IN_TRANSIT");
        ok("destination-receivings",destinationOnly,"TX-RECEIVE",receive,"RECEIVING");
        var confirmation = new LinkedHashMap<String,Object>(receive); confirmation.remove("expectedEpoch");
        var active=ok("destination-confirmations",destinationOnly,"TX-CONFIRM",confirmation,"ACTIVE");
        assertEquals("WH-B",active.path("ownerWarehouseId").asString()); assertEquals(epoch+1,active.path("ownerEpoch").asLong());
        ok("destination-receivings",destinationOnly,"TX-RECEIVE",receive,"ACTIVE");
        ok("destination-confirmations",destinationOnly,"TX-CONFIRM",confirmation,"ACTIVE");
        receive.put("expectedEpoch",epoch+3);
        assertEquals(409,post("destination-receivings",destinationOnly,"TX-STALE-RECEIVE",RuntimeMessage.JSON.writeValueAsString(receive)).statusCode());
        var transfer=http.send(HttpRequest.newBuilder(URI.create(base()+"/internal/wms/v1/serial-identities/transfers/TX-HTTP?warehouseId=WH-B&skuId=SKU&serial=SN-TRANSFER-HTTP"))
                .header("Authorization","Bearer "+destinationOnly).header("X-Wms-Enterprise-Id","ENT").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,transfer.statusCode(),transfer.body());
        var missing=Map.<String,Object>of("warehouseId","WH-B","skuId","SKU","serial","SN-TRANSFER-HTTP","factRef","MISSING-FACT","expectedEpoch",epoch+1);
        ok("missing",destinationOnly,"TX-MISSING",missing,"MISSING");
        var found=Map.<String,Object>of("warehouseId","WH-B","skuId","SKU","serial","SN-TRANSFER-HTTP","operationId","FOUND-FACT");
        ok("found-claims",destinationOnly,"TX-FOUND-CLAIM",found,"FOUND_CLAIMED");
        ok("found-activations",destinationOnly,"TX-FOUND-ACTIVATE",found,"ACTIVE");
        // 模拟调用方丢失激活回执：从同一认领动作重试，不得制造新epoch或误认其他操作。
        ok("found-claims",destinationOnly,"TX-FOUND-CLAIM",found,"ACTIVE");
        ok("found-activations",destinationOnly,"TX-FOUND-ACTIVATE",found,"ACTIVE");
        assertEquals(409,post("found-activations",destinationOnly,"TX-OTHER-FOUND",RuntimeMessage.JSON.writeValueAsString(found).replace("FOUND-FACT","OTHER-FACT")).statusCode());
        var fresh=Map.<String,Object>of("warehouseId","WH-B","skuId","SKU","serial","SN-FRESH-FOUND","operationId","FRESH-FACT");
        ok("found-claims",destinationOnly,"FRESH-CLAIM",fresh,"CLAIMED");
        ok("found-claims",destinationOnly,"FRESH-CLAIM",fresh,"CLAIMED");
        ok("found-activations",destinationOnly,"FRESH-ACTIVATE",fresh,"ACTIVE");
    }

    private tools.jackson.databind.JsonNode ok(String action,String token,String key,Map<String,Object> body,String state) throws Exception {
        var response=post(action,token,key,RuntimeMessage.JSON.writeValueAsString(body));
        assertEquals(200,response.statusCode(),response.body());
        var result=RuntimeMessage.JSON.readTree(response.body()); assertEquals(state,result.path("state").asString()); return result;
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
