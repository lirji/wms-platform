package com.lrj.wms.inventory;

import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.runtime.messaging.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实双进程双库Kafka；TCC和授权证据是明确夹具，不冒充完整TM/RM。 */
class OutboundMessagingProcessesIT {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test void splitPickShipAndCancelRecoverWithoutConsumingOtherOrderLine() throws Exception {
        Path root = Path.of("..").toRealPath(), logs = Path.of("target", "outbound-messaging-processes").toAbsolutePath();
        Files.createDirectories(logs);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate()).keyID("it").build();
        var jwks = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] publicKeys = new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jwks.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, publicKeys.length);
            try (var output = exchange.getResponseBody()) { output.write(publicKeys); }
        }); jwks.start();
        String issuer = "http://127.0.0.1:" + jwks.getAddress().getPort();
        Process outboundProcess = null, inventoryProcess = null;
        try (var outbound = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound");
                var inventory = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory");
                var kafka = new KafkaContainer("apache/kafka:3.8.0")) {
            outbound.start(); inventory.start(); kafka.start();
            var settings = new KafkaSettings(true, kafka.getBootstrapServers(), "wms.process", "PLAINTEXT", "", "");
            try (var admin = AdminClient.create(settings.connection())) {
                admin.createTopics(List.of("inventory.events", "inbound.commands", "fulfillment.results", "outbound.commands", "outbound.results")
                        .stream().map(name -> new NewTopic("wms.process." + name, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
            }
            int outboundPort = port(), inventoryPort = port();
            outboundProcess = start(root, "outbound", outbound, kafka, outboundPort, issuer, logs);
            inventoryProcess = start(root, "inventory", inventory, kafka, inventoryPort, issuer, logs);
            Process out = outboundProcess, stock = inventoryProcess;
            await(() -> health(outboundPort) && health(inventoryPort), 75, "未启动，日志=" + logs, out, stock);
            var stockDb = new JdbcTemplate(source(inventory)); var outDb = new JdbcTemplate(source(outbound));
            seed(inventory);
            String token = token(issuer, rsa), base = "http://127.0.0.1:" + outboundPort + "/api/wms/v1/warehouses/WH";
            var created = post(base + "/outbound-orders", token, "CREATE", """
                    {"allocationId":"ALLOC","attemptId":"ATT","ownerId":"OWNER","lines":[{"orderLineId":"ORDER-LINE","skuId":"SKU","qty":"5","baseUnit":"EA"}]}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            String orderPath = base + "/outbound-orders/" + RuntimeMessage.JSON.readTree(created.body()).path("id").asString();
            outDb.update("INSERT INTO outbound_tcc_evidence(id,enterprise_id,warehouse_id,attempt_id,xid,tc_observed_status,tc_terminal_evidence_ref,participant_set_hash,created_at,updated_at) VALUES ('FIXTURE','ENT','WH','ATT','fixture-xid','Committed','fixture-evidence',?,NOW(6),NOW(6))", "e".repeat(64));
            var auth = post(orderPath + "/execution-authorizations", token, "AUTH", json(Map.of("attemptId", "ATT", "authorizationId", "AUTH", "xid", "fixture-xid", "tcTerminalEvidenceRef", "fixture-evidence", "participantSetHash", "e".repeat(64))));
            assertEquals(200, auth.statusCode(), auth.body());
            var planned = post(orderPath + "/pick-tasks", token, "PLAN", json(Map.of("orderLineId", "ORDER-LINE", "sourceLocationId", "SOURCE", "stagingLocationId", "STAGE", "qty", "5")));
            assertEquals(201, planned.statusCode(), planned.body());
            String pickPath = base + "/tasks/" + RuntimeMessage.JSON.readTree(planned.body()).path("taskId").asString() + "/picks";
            assertEquals(400, post(pickPath, token, "MISSING", json(Map.of("qty", "2"))).statusCode());
            // T2最后Outbox失败：原来源已提交，库存余额/预占分批/凭证/Inbox必须整体回滚。
            stockDb.execute("ALTER TABLE outbox_event ADD CONSTRAINT ck_it_outbound_result CHECK(event_type <> 'InventoryCommandResult')");
            String pick = json(Map.of("qty", "2", "pickPartId", "PART-1", "lotId", "NO_LOT"));
            var first = post(pickPath, token, "PICK-1", pick); assertEquals(202, first.statusCode(), first.body());
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE source_service='wms-outbound' AND claim_epoch>0", Integer.class) > 0, 30, "未尝试PICK", out, stock);
            assertEquals(0, stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting", Integer.class));
            assertEquals(0, stockDb.queryForObject("SELECT COUNT(*) FROM reservation_line WHERE parent_line_id IS NOT NULL", Integer.class));
            stop(inventoryProcess); inventoryProcess = null;
            stockDb.execute("ALTER TABLE outbox_event DROP CHECK ck_it_outbound_result");
            inventoryProcess = start(root, "inventory", inventory, kafka, inventoryPort, issuer, logs); stock = inventoryProcess;
            await(() -> applied(outDb, "PICK-1"), 60, "PICK未恢复", out, stock);
            var replay = post(pickPath, token, "PICK-RETRY", pick);
            assertEquals(202, replay.statusCode(), replay.body());
            assertEquals("POSTED", RuntimeMessage.JSON.readTree(replay.body()).path("stockSyncStatus").asString());
            assertTrue(RuntimeMessage.JSON.readTree(replay.body()).path("statusUrl").asString().endsWith(orderPath.substring(base.length())));
            assertEquals(409, post(pickPath, token, "PICK-RETRY", pick.replace("NO_LOT", "CHANGED")).statusCode());
            var second = post(pickPath, token, "PICK-2", json(Map.of("qty", "1", "pickPartId", "PART-2", "lotId", "NO_LOT")));
            assertEquals(202, second.statusCode(), second.body());
            await(() -> applied(outDb, "PICK-2"), 30, "第二次PICK未过账", out, stock);
            assertEquals(2, stockDb.queryForObject("SELECT COUNT(*) FROM reservation_line WHERE order_line_id='ORDER-LINE' AND parent_line_id IS NOT NULL", Integer.class));
            decimal(stockDb, "SELECT remaining_qty FROM reservation_line WHERE order_line_id='OTHER-LINE'", "5");
            var packed = post(orderPath + "/packings", token, "PACK", json(Map.of("orderLineId", "ORDER-LINE", "packageNo", "PKG", "qty", "3")));
            assertEquals(201, packed.statusCode(), packed.body());
            String ship = json(Map.of("orderLineId", "ORDER-LINE", "qty", "3", "shipmentPartId", "SHIP-PART", "stagingLocationId", "STAGE", "lotId", "NO_LOT"));
            assertEquals(400, post(orderPath + "/shipments", token, "SHIP-WRONG", ship.replace("STAGE", "SOURCE")).statusCode());
            assertEquals(0, outDb.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='SHIP-WRONG'", Integer.class));
            var shipped = post(orderPath + "/shipments", token, "SHIP", ship); assertEquals(202, shipped.statusCode(), shipped.body());
            await(() -> applied(outDb, "SHIP"), 30, "SHIP未过账", out, stock);
            assertEquals(202, post(orderPath + "/shipments", token, "SHIP-RETRY", ship).statusCode());
            decimal(stockDb, "SELECT SUM(consumed_qty) FROM reservation_line WHERE order_line_id='ORDER-LINE'", "3");
            decimal(stockDb, "SELECT on_hand_qty FROM stock_balance WHERE location_id='STAGE'", "0");
            // T3最后Inbox失败：不得提前累计取消回执，重启后重放同一条消息。
            outDb.execute("ALTER TABLE runtime_message_inbox ADD CONSTRAINT ck_it_cancel_done CHECK(status <> 'DONE' OR JSON_UNQUOTE(JSON_EXTRACT(payload,'$.payload.commandId')) <> 'CANCEL')");
            var cancelled = post(orderPath + "/cancellations", token, "CANCEL", json(Map.of("orderLineId", "ORDER-LINE", "sourceLocationId", "SOURCE", "lotId", "NO_LOT")));
            assertEquals(202, cancelled.statusCode(), cancelled.body());
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='CANCEL'", Integer.class) == 1
                    && outDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.payload.commandId'))='CANCEL' AND claim_epoch>0", Integer.class) > 0,
                    30, "CANCEL未执行", out, stock);
            decimal(outDb, "SELECT cancelled_posted_qty FROM outbound_line", "0");
            assertEquals("PENDING", outDb.queryForObject("SELECT stock_sync_status FROM outbound_line", String.class));
            stop(outboundProcess); outboundProcess = null; outDb.execute("ALTER TABLE runtime_message_inbox DROP CHECK ck_it_cancel_done");
            outboundProcess = start(root, "outbound", outbound, kafka, outboundPort, issuer, logs); out = outboundProcess;
            await(() -> applied(outDb, "CANCEL"), 60, "CANCEL回执未恢复", out, stock);
            decimal(outDb, "SELECT cancelled_posted_qty FROM outbound_line", "2");
            assertEquals("POSTED", outDb.queryForObject("SELECT stock_sync_status FROM outbound_line", String.class));
            assertEquals(0, outDb.queryForObject("SELECT COUNT(*) FROM outbound_task WHERE task_type='RESTOCK'", Integer.class));
            decimal(stockDb, "SELECT on_hand_qty FROM stock_balance WHERE location_id='SOURCE'", "7");
            decimal(stockDb, "SELECT reserved_qty FROM stock_balance WHERE location_id='SOURCE'", "5");
            decimal(stockDb, "SELECT remaining_qty FROM reservation_line WHERE order_line_id='OTHER-LINE'", "5");
            assertEquals(4, stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting", Integer.class));
            decimal(outDb, "SELECT picked_posted_qty FROM outbound_bucket_progress", "3");
            decimal(outDb, "SELECT shipped_physical_qty FROM outbound_bucket_progress", "3");
            assertEquals(0, stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE source_execution_id='NO_SOURCE_EXECUTION'", Integer.class));
            var result = RuntimeMessage.JSON.readTree(stockDb.queryForObject("SELECT payload FROM outbox_event WHERE event_type='InventoryCommandResult' AND aggregate_id='CANCEL'", String.class));
            try (var publisher = new KafkaMessagePublisher(settings, "outbound-replay-probe")) {
                publisher.publish("wms.process.outbound.results", "CANCEL", new RuntimeMessage(1, "CANCEL-DUPLICATE", "wms-inventory", "ENT", "WH", "InventoryCommandResult", "CANCEL", 1, Instant.now().toString(), "probe", result).encode());
                publisher.publish("wms.process.outbound.results", "WRONG", new RuntimeMessage(1, "WRONG-RESULT-ENVELOPE", "wms-inventory", "ENT", "WH", "InventoryCommandResult", "OTHER", 1, Instant.now().toString(), "probe", result).encode());
                publisher.publish("wms.process.outbound.commands", "WRONG", new RuntimeMessage(1, "SOURCE-ACTION-MISMATCH", "wms-outbound", "ENT", "WH", "StockCommandRequested", "WRONG", 1, Instant.now().toString(), "probe", RuntimeMessage.JSON.createObjectNode().put("action", "RECEIVE")).encode());
            }
            await(() -> outDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE payload LIKE '%CANCEL-DUPLICATE%' AND status='DONE'", Integer.class) == 1
                    && stockDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE error_code='UNSUPPORTED_COMMAND_ACTION' AND status='ISOLATED'", Integer.class) == 1
                    && outDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE error_code='RESULT_ENVELOPE_MISMATCH' AND status='ISOLATED'", Integer.class) == 1,
                    30, "重复或错误动作未处理", out, stock);
            decimal(outDb, "SELECT cancelled_posted_qty FROM outbound_line", "2");
            assertEquals(4, stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting", Integer.class));
        } finally { stop(inventoryProcess); stop(outboundProcess); jwks.stop(0); }
    }

    private static String json(Object value) { return RuntimeMessage.JSON.writeValueAsString(value); }
    private static boolean applied(JdbcTemplate jdbc, String id) { return "APPLIED".equals(jdbc.queryForObject("SELECT state FROM source_command WHERE command_id=?", String.class, id)); }
    private static void decimal(JdbcTemplate jdbc, String sql, String expected) { assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(sql, BigDecimal.class)), sql); }
    private static void seed(MySQLContainer inventory) {
        var config = new Configuration(new Environment("inventory-fixtures", new JdbcTransactionFactory(), source(inventory)));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        for (var mapper : List.of(MasterdataMapper.class, com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class,
                com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper.class,
                com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper.class)) config.addMapper(mapper);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(false)) {
            var md = new MasterdataService(session, Clock.systemUTC());
            md.createWarehouse("WH", "ENT", "WH", "测试仓", "UTC");
            md.createLocation("SOURCE", "GATE-SOURCE", "ENT", "WH", "SOURCE", "A", "STORAGE", new BigDecimal("100"), "EA");
            md.createLocation("STAGE", "GATE-STAGE", "ENT", "WH", "STAGE", "A", "STAGING", new BigDecimal("100"), "EA");
            md.createSku(SkuPolicy.create("SKU", "ENT", "SKU", "测试商品", "EA", 0, false, false, false, 1, "ACTIVE"), "UNIT");
            var bucket = com.lrj.wms.inventory.inventory.domain.StockBucketKey.of("ENT", "WH", "OWNER", "SOURCE", "SKU", "NO_LOT", "GOOD");
            var app = new com.lrj.wms.inventory.inventory.InventoryApplicationService(session, Clock.systemUTC());
            var five = com.lrj.wms.inventory.inventory.domain.Quantity.parse("5", 0);
            app.receive("ENT", "WH", "SEED-RECEIVE", "SEED", "fixture", bucket, com.lrj.wms.inventory.inventory.domain.Quantity.parse("10", 0));
            app.reserveTried("ENT", "WH", "SEED-TRY", "SEED", "fixture", "ALLOC", "ATT", "fixture-xid", 1L, "ReservationTccAction", 1L, "d".repeat(64),
                    List.of(new com.lrj.wms.inventory.inventory.ReservationLineInput(bucket, five, "OTHER-LINE"),
                            new com.lrj.wms.inventory.inventory.ReservationLineInput(bucket, five, "ORDER-LINE")));
            app.confirmTried("ENT", "WH", "SEED-CONFIRM", "SEED", "fixture", "ALLOC", "ATT", "fixture-xid", 1L, "ReservationTccAction");
            session.commit();
        }
    }
    private Process start(Path root, String service, MySQLContainer db, KafkaContainer kafka, int port, String issuer, Path logs) throws Exception {
        Path jar = root.resolve("wms-" + service + "/target/wms-" + service + "-0.1.0-SNAPSHOT.jar");
        assertTrue(Files.isRegularFile(jar), "缺少本次构建服务Jar：" + jar);
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m", "-jar", jar.toString());
        var env = builder.environment();
        env.put("WMS_HTTP_PORT", String.valueOf(port)); env.put("WMS_BIND_ADDRESS", "127.0.0.1");
        env.put("WMS_" + service.toUpperCase(Locale.ROOT) + "_JDBC_URL", db.getJdbcUrl());
        env.put("WMS_" + service.toUpperCase(Locale.ROOT) + "_DB_USER", db.getUsername());
        env.put("WMS_" + service.toUpperCase(Locale.ROOT) + "_DB_PASSWORD", db.getPassword());
        env.put("WMS_OIDC_ISSUER", issuer); env.put("WMS_OIDC_JWK_SET_URI", issuer + "/jwks"); env.put("WMS_OIDC_CLIENT_ID", "wms-platform");
        env.put("WMS_MESSAGING_RECOVERYENABLED", "true");
        env.put("WMS_MESSAGING_ENABLED", "true"); env.put("WMS_MESSAGING_BOOTSTRAPSERVERS", kafka.getBootstrapServers());
        env.put("WMS_MESSAGING_TOPICPREFIX", "wms.process");
        return builder.redirectErrorStream(true).redirectOutput(logs.resolve(service + ".log").toFile()).start();
    }
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {
        var source = new com.mysql.cj.jdbc.MysqlDataSource(); source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(), "UTC")); source.setUser(db.getUsername()); source.setPassword(db.getPassword()); return source;
    }
    private HttpResponse<String> post(String url, String token, String key, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private boolean health(int port) {
        try { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
        catch (Exception unavailable) { return false; }
    }
    private static void await(BooleanSupplier done, int seconds, String failure, Process... processes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean()) {
            for (var process : processes) assertTrue(process.isAlive(), failure);
            assertTrue(System.nanoTime() < deadline, failure);
            Thread.sleep(250);
        }
    }
    private static int port() throws Exception { try (var socket = new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static void stop(Process process) throws Exception {
        if (process == null) return; process.destroy(); if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
    }
    private static String token(String issuer, RSAKey rsa) throws Exception {
        return token(issuer, rsa, List.of("outbound.create", "outbound.read", "outbound.pick", "outbound.pack", "outbound.ship", "fulfillment.execute"));
    }
    private static String token(String issuer, RSAKey rsa, List<String> scopes) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject("operator-process")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).claim("enterprise_id", "ENT").claim("warehouses", List.of("WH"))
                .claim("scope", scopes).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("it").build(), claims); jwt.sign(new RSASSASigner(rsa)); return jwt.serialize();
    }
}
