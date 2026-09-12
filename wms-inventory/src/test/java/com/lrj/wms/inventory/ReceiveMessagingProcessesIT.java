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

/** 两个独立可执行服务、两库与真实Kafka，验证HTTP收货到T3回执及故障恢复。 */
class ReceiveMessagingProcessesIT {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test void receiptSurvivesBrokerOutageAndUpdatesSourceExactlyOnce() throws Exception {
        Path root = Path.of("..").toRealPath();
        Path logs = Path.of("target", "receive-messaging-processes").toAbsolutePath();
        Files.createDirectories(logs);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) keys.getPublic()).privateKey((RSAPrivateKey) keys.getPrivate()).keyID("it").build();
        var jwks = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] publicKeys = new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jwks.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, publicKeys.length);
            try (var body = exchange.getResponseBody()) { body.write(publicKeys); }
        });
        jwks.start();
        String issuer = "http://127.0.0.1:" + jwks.getAddress().getPort();
        Process inboundProcess = null, inventoryProcess = null;
        try (var inbound = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inbound");
                var inventory = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory");
                var kafka = new KafkaContainer("apache/kafka:3.8.0")) {
            inbound.start(); inventory.start(); kafka.start();
            var settings = new KafkaSettings(true, kafka.getBootstrapServers(), "wms.process", "PLAINTEXT", "", "");
            try (var admin = AdminClient.create(settings.connection())) {
                admin.createTopics(List.of(new NewTopic("wms.process.inventory.events", 1, (short) 1),
                        new NewTopic("wms.process.inbound.commands", 1, (short) 1), new NewTopic("wms.process.inbound.results", 1, (short) 1)))
                        .all().get(20, TimeUnit.SECONDS);
            }
            int inboundPort = port(), inventoryPort = port();
            inboundProcess = start(root, "inbound", inbound, kafka, inboundPort, issuer, logs);
            inventoryProcess = start(root, "inventory", inventory, kafka, inventoryPort, issuer, logs);
            Process in = inboundProcess, stock = inventoryProcess;
            await(() -> health(inboundPort) && health(inventoryPort), 75, "两个真实服务未启动，日志=" + logs, in, stock);
            var stockSource = source(inventory);
            var configuration = new Configuration(new Environment("seed-only", new JdbcTransactionFactory(), stockSource));
            configuration.addMapper(MasterdataMapper.class);
            try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(false)) {
                var masterdata = new MasterdataService(session, Clock.systemUTC());
                masterdata.createWarehouse("WH", "ENT", "WH", "测试仓", "UTC");
                masterdata.createLocation("LOC", "GATE", "ENT", "WH", "LOC", "A", "RECEIVING", new BigDecimal("100"), "EA");
                masterdata.createSku(SkuPolicy.create("SKU", "ENT", "SKU", "测试商品", "EA", 0, false, false, false, 1, "ACTIVE"), "UNIT");
                session.commit();
            }
            String token = token(issuer, rsa);
            String base = "http://127.0.0.1:" + inboundPort + "/api/wms/v1/warehouses/WH";
            var created = post(base + "/inbound-orders", token, "ORDER", """
                    {"sourceSystem":"ERP","externalNo":"EXT","ownerId":"OWNER","lines":[
                    {"lineId":"LINE","externalLineId":"EXT-LINE","skuId":"SKU","expectedQty":"3","unit":"EA"}]}
                    """);
            assertEquals(201, created.statusCode(), created.body());
            String receiptPath = base + "/inbound-orders/ORDER/receipts";
            var missing = post(receiptPath, token, "NO-CONTEXT", "{\"lineId\":\"LINE\",\"qty\":\"3\"}");
            assertEquals(400, missing.statusCode(), missing.body());
            String receipt = "{\"lineId\":\"LINE\",\"qty\":\"3\",\"receiptPartId\":\"PART\",\"locationId\":\"LOC\",\"lotId\":\"NO_LOT\"}";
            var inDb = new JdbcTemplate(source(inbound)); var stockDb = new JdbcTemplate(stockSource);
            kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
            try {
                var accepted = post(receiptPath, token, "RECEIVE-CMD", receipt);
                assertEquals(202, accepted.statusCode(), accepted.body());
                assertEquals("PENDING", inDb.queryForObject("SELECT state FROM source_command WHERE command_id='RECEIVE-CMD'", String.class));
                assertEquals(0, stockDb.queryForObject("SELECT COUNT(*) FROM stock_ledger", Integer.class));
            } finally { kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec(); }
            await(() -> "APPLIED".equals(inDb.queryForObject("SELECT state FROM source_command WHERE command_id='RECEIVE-CMD'", String.class)),
                    60, "回执未完成，日志=" + logs, in, stock);
            assertEquals(202, post(receiptPath, token, "RECEIVE-RETRY", receipt).statusCode());
            assertEquals(1, stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting", Integer.class));
            assertEquals(1, stockDb.queryForObject("SELECT COUNT(*) FROM stock_ledger", Integer.class));
            assertEquals("HOLD", stockDb.queryForObject("SELECT quality_code FROM stock_balance", String.class));
            assertEquals(0, stockDb.queryForObject("SELECT on_hand_qty FROM stock_balance", BigDecimal.class).compareTo(new BigDecimal("3")));
            assertEquals(0, inDb.queryForObject("SELECT received_posted_qty FROM inbound_line WHERE id='LINE'", BigDecimal.class).compareTo(new BigDecimal("3")));
            assertEquals("operator-process", stockDb.queryForObject("SELECT actor_id FROM stock_ledger", String.class));
            // 重新投递不同事件ID的同一真实回执，来源终态仍必须阻止二次累计。
            var result = RuntimeMessage.JSON.readTree(stockDb.queryForObject("SELECT payload FROM outbox_event WHERE event_type='InventoryCommandResult'", String.class));
            try (var publisher = new KafkaMessagePublisher(settings, "duplicate-result-probe")) {
                publisher.publish("wms.process.inbound.results", "RECEIVE-CMD", new RuntimeMessage(1, "REDELIVERED-RESULT", "wms-inventory", "ENT", "WH",
                        "InventoryCommandResult", "RECEIVE-CMD", 1, Instant.now().toString(), "replay-probe", result).encode());
            }
            await(() -> inDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE status='DONE'", Integer.class) == 2,
                    20, "重复回执未消费", in, stock);
            assertEquals(0, inDb.queryForObject("SELECT received_posted_qty FROM inbound_line WHERE id='LINE'", BigDecimal.class).compareTo(new BigDecimal("3")));
            assertEquals(1, inDb.queryForObject("SELECT COUNT(*) FROM source_execution", Integer.class));
            // 正常退出先停业务进程，再关闭专属组件，验证期间不制造无关的连接中断噪声。
            stop(inboundProcess); inboundProcess = null;
            stop(inventoryProcess); inventoryProcess = null;
        } finally {
            stop(inboundProcess); stop(inventoryProcess); jwks.stop(0);
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
        env.put("WMS_MESSAGING_ENABLED", "true"); env.put("WMS_MESSAGING_BOOTSTRAPSERVERS", kafka.getBootstrapServers());
        env.put("WMS_MESSAGING_TOPICPREFIX", "wms.process");
        return builder.redirectErrorStream(true).redirectOutput(logs.resolve(service + ".log").toFile()).start();
    }
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {
        var source = new com.mysql.cj.jdbc.MysqlDataSource(); source.setUrl(db.getJdbcUrl()); source.setUser(db.getUsername()); source.setPassword(db.getPassword()); return source;
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
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject("operator-process")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).claim("enterprise_id", "ENT").claim("warehouses", List.of("WH"))
                .claim("scope", List.of("inbound.create", "inbound.read", "inbound.receive")).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("it").build(), claims); jwt.sign(new RSASSASigner(rsa)); return jwt.serialize();
    }
}
