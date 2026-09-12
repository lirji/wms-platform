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
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private DataSource dataSource;

    @Autowired private org.apache.ibatis.session.SqlSessionFactory sessions;

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
        registry.add("wms.reconciliation.window-enabled", () -> "true");
        registry.add("wms.reconciliation.allowed-subjects", () -> "wms-wh-a");
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
        assertTrue(created.body().contains("PENDING_AUTHORIZATION"));
        HttpResponse<String> unverified = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/pick-tasks",
                token, "BARE-AUTH", "{\"orderLineId\":\"OL-1\",\"sourceLocationId\":\"LOC-P\",\"stagingLocationId\":\"LOC-S\",\"qty\":\"3\"}");
        assertEquals(409, unverified.statusCode());
        assertTrue(unverified.body().contains("AUTH_REQUIRED"));
        new JdbcTemplate(dataSource).update("INSERT INTO outbound_tcc_evidence (id,enterprise_id,warehouse_id,attempt_id,xid,tc_observed_status,tc_terminal_evidence_ref,participant_set_hash,created_at,updated_at) VALUES ('FIRST-EV','ENT-1','WH-A','ATT-1','first-xid','Committed','first-evidence',?,NOW(6),NOW(6))", "b".repeat(64));
        String verifiedBody = "{\"attemptId\":\"ATT-1\",\"authorizationId\":\"AUTH-1\",\"xid\":\"first-xid\",\"tcTerminalEvidenceRef\":\"first-evidence\",\"participantSetHash\":\"" + "b".repeat(64) + "\"}";
        String authPath = "/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/execution-authorizations";
        assertEquals(200, post(authPath, token, "FIRST-AUTH", verifiedBody).statusCode());
        assertEquals(409, post(authPath, token, "FIRST-AUTH", verifiedBody.replace("first-xid", "other-xid")).statusCode());
        HttpResponse<String> planned = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/pick-tasks",
                token, "CMD-PLAN-1", "{\"orderLineId\":\"OL-1\",\"sourceLocationId\":\"LOC-P\","
                        + "\"stagingLocationId\":\"LOC-S\",\"qty\":\"3\"}");
        assertEquals(201, planned.statusCode());
        HttpResponse<String> authReplayAfterPicking = post(authPath, token, "ANOTHER-AUTH-KEY", verifiedBody);
        assertEquals(200, authReplayAfterPicking.statusCode());
        assertTrue(authReplayAfterPicking.body().contains("PICKING"));
        String taskId = textBetween(planned.body(), "\"taskId\":\"", "\"");
        HttpResponse<String> tasks = get("/api/wms/v1/warehouses/WH-A/tasks?taskType=PICK", token);
        assertEquals(200, tasks.statusCode());
        assertTrue(tasks.body().contains(taskId));
        HttpResponse<String> task = get("/api/wms/v1/warehouses/WH-A/tasks/" + taskId, token);
        assertEquals(200, task.statusCode());
        HttpResponse<String> claimed = post("/api/wms/v1/warehouses/WH-A/tasks/" + taskId + "/claims", token,
                "CMD-CLAIM-1", "{\"expectedVersion\":0}");
        assertEquals(200, claimed.statusCode());
        assertTrue(claimed.body().contains("\"claimEpoch\":1"));
        HttpResponse<String> wrongType = get("/api/wms/v1/warehouses/WH-A/tasks?taskType=PUTAWAY", token);
        assertEquals(400, wrongType.statusCode());
        HttpResponse<String> picked = post("/api/wms/v1/warehouses/WH-A/tasks/" + taskId + "/picks", token, "CMD-PICK-1",
                "{\"qty\":\"2\",\"lotId\":\"NO_LOT\"}");
        assertEquals(202, picked.statusCode());
        HttpResponse<String> packed = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/packings", token,
                "CMD-PACK-1", "{\"orderLineId\":\"OL-1\",\"qty\":\"2\"}");
        assertEquals(201, packed.statusCode());
        HttpResponse<String> shipped = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/shipments",
                token, "CMD-SHIP-1", "{\"orderLineId\":\"OL-1\",\"qty\":\"1\",\"stagingLocationId\":\"LOC-S\",\"lotId\":\"NO_LOT\"}");
        assertEquals(400, shipped.statusCode(), shipped.body());
        assertTrue(shipped.body().contains("PICK_POSTING_PENDING"));
        // 本HTTP测试没有库存进程；明确用领域回执夹具验证发运屏障，不冒充消息链路。
        try (var session = sessions.openSession(false)) {
            String lineId = new JdbcTemplate(dataSource).queryForObject("SELECT id FROM outbound_line WHERE order_id=? AND order_line_id='OL-1'", String.class, orderId);
            new OutboundOrderService(session, java.time.Clock.systemUTC()).consumePick("ENT-1", "WH-A", lineId,
                    "FIXTURE-PICK-RESULT", "CMD-PICK-1", "APPLIED", "FIXTURE-PICK-POSTING", new java.math.BigDecimal("2"));
            session.commit();
        }
        shipped = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/shipments", token, "CMD-SHIP-1",
                "{\"orderLineId\":\"OL-1\",\"qty\":\"1\",\"stagingLocationId\":\"LOC-S\",\"lotId\":\"NO_LOT\"}");
        assertEquals(202, shipped.statusCode(), shipped.body());
        HttpResponse<String> cancelled = post("/api/wms/v1/warehouses/WH-A/outbound-orders/" + orderId + "/cancellations",
                token, "CMD-CXL-1", "{\"orderLineId\":\"OL-1\",\"sourceLocationId\":\"LOC-P\",\"lotId\":\"NO_LOT\",\"qty\":\"1\"}");
        assertEquals(202, cancelled.statusCode(), cancelled.body());
        assertEquals(0, new java.math.BigDecimal("1").compareTo(new JdbcTemplate(dataSource).queryForObject(
                "SELECT cancelled_qty FROM outbound_line WHERE order_id=?", java.math.BigDecimal.class, orderId)));
        var cancelledTask = post("/api/wms/v1/warehouses/WH-A/tasks/" + taskId + "/picks", token, "AFTER-CANCEL",
                "{\"qty\":\"1\",\"lotId\":\"NO_LOT\"}");
        assertEquals(400, cancelledTask.statusCode(), cancelledTask.body());
        assertTrue(cancelledTask.body().contains("TASK_NOT_EXECUTABLE"));
        HttpResponse<String> forbidden = get("/api/wms/v1/warehouses/WH-B/outbound-orders", token);
        assertEquals(403, forbidden.statusCode());
        String exec = token(List.of("WH-A"), List.of("outbound.create", "outbound.read", "fulfillment.execute"));
        HttpResponse<String> pending = post("/api/wms/v1/warehouses/WH-A/outbound-orders", exec, "KEY-OB-AUTH",
                "{\"allocationId\":\"ALLOC-AUTH\",\"attemptId\":\"ATT-AUTH\",\"ownerId\":\"OWNER-1\","
                        + "\"lines\":[{\"orderLineId\":\"OL-A\",\"skuId\":\"SKU-STD\",\"qty\":\"2\",\"baseUnit\":\"EA\"}]}");
        assertEquals(201, pending.statusCode());
        assertTrue(pending.body().contains("PENDING_AUTHORIZATION"));
        String pendingId = textBetween(pending.body(), "\"id\":\"", "\"");
        HttpResponse<String> noEvidence = post(
                "/api/wms/v1/warehouses/WH-A/outbound-orders/" + pendingId + "/execution-authorizations", exec,
                "KEY-AUTH-1", "{\"attemptId\":\"ATT-AUTH\",\"authorizationId\":\"AUTH-REAL\","
                        + "\"xid\":\"xid-1\",\"tcTerminalEvidenceRef\":\"ev-1\",\"participantSetHash\":\""
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}");
        assertEquals(409, noEvidence.statusCode());
        assertTrue(noEvidence.body().contains("TCC_NOT_COMMITTED"));
        new JdbcTemplate(dataSource).update(
                "INSERT INTO outbound_tcc_evidence (id, enterprise_id, warehouse_id, attempt_id, xid, "
                        + "tc_observed_status, tc_terminal_evidence_ref, participant_set_hash, version, created_at, "
                        + "updated_at) VALUES ('EV-1','ENT-1','WH-A','ATT-AUTH','xid-1','Committed','ev-1',"
                        + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,UTC_TIMESTAMP(6),"
                        + "UTC_TIMESTAMP(6))");
        HttpResponse<String> authorized = post(
                "/api/wms/v1/warehouses/WH-A/outbound-orders/" + pendingId + "/execution-authorizations", exec,
                "KEY-AUTH-1", "{\"attemptId\":\"ATT-AUTH\",\"authorizationId\":\"AUTH-REAL\","
                        + "\"xid\":\"xid-1\",\"tcTerminalEvidenceRef\":\"ev-1\",\"participantSetHash\":\""
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}");
        assertEquals(200, authorized.statusCode());
        assertTrue(authorized.body().contains("AUTH-REAL"));
        assertTrue(!authorized.body().contains("\"attemptState\":\"ALLOCATED\""));
        HttpResponse<String> replayAuth = post(
                "/api/wms/v1/warehouses/WH-A/outbound-orders/" + pendingId + "/execution-authorizations", exec,
                "KEY-AUTH-1", "{\"attemptId\":\"ATT-AUTH\",\"authorizationId\":\"AUTH-REAL\","
                        + "\"xid\":\"xid-1\",\"tcTerminalEvidenceRef\":\"ev-1\",\"participantSetHash\":\""
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}");
        assertEquals(200, replayAuth.statusCode());
    }

    @Test
    void serialQuantityMismatchIsRejectedBeforeTaskLookup() throws Exception {
        var response = post("/api/wms/v1/warehouses/WH-A/tasks/UNKNOWN/picks", token(List.of("WH-A")), "BAD-SERIAL-QTY",
                """
                {"lotId":"NO_LOT","qty":2,"serialExecution":{"schemaVersion":1,
                "identities":[{"serialId":"SN-ONE","ownerEpoch":1}]}}
                """);
        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void sourceWindowRequiresTrustedSubjectAndReturnsOriginalScopedProof() throws Exception {
        String path="/internal/wms/v1/warehouses/WH-WINDOW/reconciliation-windows/HTTP-CUT";
        String body="{\"cutoff\":\"2026-09-12T00:00:00Z\"}";
        String trusted=token(List.of("WH-WINDOW"),List.of("recon.evidence"));
        assertEquals(403,post(path,token(List.of("WH-WINDOW"),List.of("recon.read")),"CUT",body).statusCode());
        assertEquals(403,post(path,token(List.of("WH-WINDOW"),List.of("recon.evidence"),"untrusted"),"CUT",body).statusCode());
        assertEquals(403,post(path,token(List.of("WH-OTHER"),List.of("recon.evidence")),"CUT",body).statusCode());
        assertEquals(400,post(path,trusted,"CUT","{}").statusCode());
        var response=post(path,trusted,"CUT",body);assertEquals(200,response.statusCode(),response.body());
        var proof=com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.readTree(response.body());
        assertEquals("COMPLETE",proof.path("state").asString());assertEquals(0,proof.path("factCount").asInt());
        assertEquals(response.body(),post(path,trusted,"CUT-REPLAY",body).body());
        assertEquals(400,post(path,trusted,"CUT-CHANGED","{\"cutoff\":\"2026-09-12T00:00:01Z\"}").statusCode());
        var page=get(path+"/facts?cutoff=2026-09-12T00%3A00%3A00Z",trusted);assertEquals(200,page.statusCode(),page.body());
        var facts=com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.readTree(page.body());
        assertEquals("wms-outbound",facts.path("sourceService").asString());assertEquals(proof.path("digest"),facts.path("digest"));
        assertTrue(facts.path("facts").isEmpty());
        // 原来源T1已提交但尚无T3；HTTP不能输出可被当作完整水位的事实页。
        try(var session=sessions.openSession(false)) {
            new com.lrj.wms.outbound.protocol.SourceProtocolService(session,java.time.Clock.fixed(java.time.Instant.parse("2026-09-11T00:00:00Z"),java.time.ZoneOffset.UTC))
                    .submitShip("ENT-1","WH-PENDING","PENDING-WINDOW","ORDER","PART","LINE","fixture",java.math.BigDecimal.ONE);
            session.commit();
        }
        String pending="/internal/wms/v1/warehouses/WH-PENDING/reconciliation-windows/PENDING-CUT";
        String pendingToken=token(List.of("WH-PENDING"),List.of("recon.evidence"));
        assertEquals(202,post(pending,pendingToken,"PENDING",body).statusCode());
        var incomplete=get(pending+"/facts?cutoff=2026-09-12T00%3A00%3A00Z",pendingToken);
        assertEquals(409,incomplete.statusCode(),incomplete.body());assertTrue(incomplete.body().contains("SOURCE_INCOMPLETE"));
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
        return token(warehouses, List.of("fulfillment.execute", "outbound.read", "outbound.pick", "outbound.pack", "outbound.ship", "task.read", "task.claim"));
    }

    private static String token(List<String> warehouses, List<String> scopes) throws Exception {
        return token(warehouses,scopes,"wms-wh-a");
    }
    private static String token(List<String> warehouses,List<String> scopes,String subject) throws Exception {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience("wms-platform")
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000)).claim("enterprise_id", "ENT-1")
                .claim("warehouses", warehouses).claim("scope", scopes).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
        jwt.sign(new RSASSASigner(rsa));
        return jwt.serialize();
    }
}
