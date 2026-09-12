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
            // 序列PICK使用同一真实来源/库存进程与Kafka；身份授权和TC仍是明确前置夹具。
            seedSerial(inventory);
            String selectable="http://127.0.0.1:"+inventoryPort+"/api/wms/v1/warehouses/WH/serial-stock?ownerId=OWNER&skuId=SERIAL-SKU&locationId=SOURCE&limit=1";
            String reader=token(issuer,rsa,List.of("inventory.read"));
            assertEquals(403,get(selectable,token).statusCode());
            var choices=get(selectable,reader);assertEquals(200,choices.statusCode(),choices.body());
            var firstChoice=RuntimeMessage.JSON.readTree(choices.body());assertEquals(1,firstChoice.path("items").size());assertEquals(1,firstChoice.path("items").get(0).path("ownerEpoch").asInt());
            String cursor=firstChoice.path("nextCursor").asString();
            var nextChoice=get(selectable+"&cursor="+cursor,reader);assertEquals(200,nextChoice.statusCode(),nextChoice.body());
            assertNotEquals(firstChoice.path("items").get(0).path("serialId").asString(),RuntimeMessage.JSON.readTree(nextChoice.body()).path("items").get(0).path("serialId").asString());
            assertEquals(400,get(selectable+"&cursor="+cursor+"&lotId=OTHER",reader).statusCode());assertEquals(400,get(selectable.replace("limit=1","limit=201"),reader).statusCode());
            assertEquals(403,get(selectable.replace("/WH/","/FOREIGN/"),reader).statusCode());
            var serialOrder=post(base+"/outbound-orders",token,"CREATE-SERIAL",json(Map.of("allocationId","ALLOC-SERIAL","attemptId","ATT-SERIAL","ownerId","OWNER","lines",List.of(Map.of("orderLineId","SERIAL-LINE","skuId","SERIAL-SKU","qty","3","baseUnit","EA")))));
            assertEquals(201,serialOrder.statusCode(),serialOrder.body());
            String serialOrderPath=base+"/outbound-orders/"+RuntimeMessage.JSON.readTree(serialOrder.body()).path("id").asString();
            outDb.update("INSERT INTO outbound_tcc_evidence(id,enterprise_id,warehouse_id,attempt_id,xid,tc_observed_status,tc_terminal_evidence_ref,participant_set_hash,created_at,updated_at) VALUES ('FIXTURE-SERIAL','ENT','WH','ATT-SERIAL','fixture-serial-xid','Committed','fixture-serial-evidence',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))","e".repeat(64));
            assertEquals(200,post(serialOrderPath+"/execution-authorizations",token,"AUTH-SERIAL",json(Map.of("attemptId","ATT-SERIAL","authorizationId","AUTH-SERIAL","xid","fixture-serial-xid","tcTerminalEvidenceRef","fixture-serial-evidence","participantSetHash","e".repeat(64)))).statusCode());
            var serialPlan=post(serialOrderPath+"/pick-tasks",token,"PLAN-SERIAL",json(Map.of("orderLineId","SERIAL-LINE","sourceLocationId","SOURCE","stagingLocationId","STAGE","qty","3")));
            assertEquals(201,serialPlan.statusCode(),serialPlan.body());
            String serialPickPath=base+"/tasks/"+RuntimeMessage.JSON.readTree(serialPlan.body()).path("taskId").asString()+"/picks";
            var serialPick=json(Map.of("qty","2","pickPartId","SERIAL-PART","lotId","NO_LOT","serialExecution",Map.of("schemaVersion",1,"identities",List.of(Map.of("serialId","PROCESS-SN-1","ownerEpoch",1),Map.of("serialId","PROCESS-SN-2","ownerEpoch",1)))));
            assertEquals(400,post(serialPickPath,token,"SERIAL-BAD-VERSION",serialPick.replace("\"schemaVersion\":1","\"schemaVersion\":1.5")).statusCode());
            assertEquals(400,post(serialPickPath,token,"SERIAL-BAD-EPOCH",serialPick.replace("\"ownerEpoch\":1","\"ownerEpoch\":1.5")).statusCode());
            stockDb.execute("ALTER TABLE serial_pick_fact ADD CONSTRAINT fail_process_serial_pick CHECK(serial_id<>'PROCESS-SN-2')");
            var serialAccepted=post(serialPickPath,token,"PROCESS-SERIAL-PICK",serialPick);assertEquals(202,serialAccepted.statusCode(),serialAccepted.body());
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE source_service='wms-outbound' AND payload LIKE '%PROCESS-SERIAL-PICK%' AND claim_epoch>0",Integer.class)>0,30,"序列PICK未尝试",out,stock);
            assertEquals(0,stockDb.queryForObject("SELECT COUNT(*) FROM serial_pick_fact",Integer.class));
            decimal(stockDb,"SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL-SKU' AND location_id='SOURCE'","5");
            stop(inventoryProcess);inventoryProcess=null;stockDb.execute("ALTER TABLE serial_pick_fact DROP CHECK fail_process_serial_pick");
            inventoryProcess=start(root,"inventory",inventory,kafka,inventoryPort,issuer,logs);stock=inventoryProcess;
            await(() -> applied(outDb,"PROCESS-SERIAL-PICK"),60,"序列PICK未从持久原身份恢复",out,stock);
            assertEquals(2,outDb.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE command_id='PROCESS-SERIAL-PICK' AND state='PICKED'",Integer.class));
            assertEquals(2,stockDb.queryForObject("SELECT COUNT(*) FROM serial_pick_fact WHERE command_id='PROCESS-SERIAL-PICK' AND order_line_id='SERIAL-LINE' AND owner_epoch=1",Integer.class));
            assertEquals(2,stockDb.queryForObject("SELECT COUNT(*) FROM local_serial s JOIN stock_balance b ON b.id=s.balance_id WHERE s.sku_id='SERIAL-SKU' AND b.location_id='STAGE'",Integer.class));
            decimal(stockDb,"SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL-SKU' AND location_id='STAGE'","2");
            var serialReplay=post(serialPickPath,token,"SERIAL-REPLAY",serialPick.replace("PROCESS-SN-1","process-sn-1"));assertEquals(202,serialReplay.statusCode(),serialReplay.body());
            assertEquals(409,post(serialPickPath,token,"SERIAL-REPLAY",serialPick.replace("PROCESS-SN-1","PROCESS-SN-3")).statusCode());
            var afterPick=get(selectable.replace("limit=1","limit=200"),reader);assertEquals(200,afterPick.statusCode(),afterPick.body());assertEquals(3,RuntimeMessage.JSON.readTree(afterPick.body()).path("items").size());
            var stagedPick=get(selectable.replace("locationId=SOURCE","locationId=STAGE"),reader);assertEquals(200,stagedPick.statusCode(),stagedPick.body());assertEquals(0,RuntimeMessage.JSON.readTree(stagedPick.body()).path("items").size());
            // 从实际入箱信封取发布器补齐后的上下文，保证探针抵达目标策略校验。
            var originalSerial=(tools.jackson.databind.node.ObjectNode)RuntimeMessage.parse(stockDb.queryForObject("SELECT payload FROM runtime_message_inbox WHERE source_service='wms-outbound' AND status='DONE' AND payload LIKE '%PROCESS-SERIAL-PICK%' LIMIT 1",String.class)).payload();
            assertEquals(2,originalSerial.path("outboundSchemaVersion").asInt());
            var downgraded=originalSerial.deepCopy();downgraded.put("outboundSchemaVersion",1);
            var wrongPolicy=originalSerial.deepCopy();((tools.jackson.databind.node.ObjectNode)wrongPolicy.path("postingContext")).put("skuId","SKU");
            try(var publisher=new KafkaMessagePublisher(settings,"serial-version-probe")) {
                publisher.publish("wms.process.outbound.commands","PROCESS-SERIAL-PICK",new RuntimeMessage(1,"SERIAL-DOWNGRADE","wms-outbound","ENT","WH","StockCommandRequested","PROCESS-SERIAL-PICK",1,Instant.now().toString(),"probe",downgraded).encode());
                publisher.publish("wms.process.outbound.commands","PROCESS-SERIAL-PICK",new RuntimeMessage(1,"SERIAL-WRONG-POLICY","wms-outbound","ENT","WH","StockCommandRequested","PROCESS-SERIAL-PICK",1,Instant.now().toString(),"probe",wrongPolicy).encode());
            }
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE status='ISOLATED' AND (payload LIKE '%SERIAL-DOWNGRADE%' OR payload LIKE '%SERIAL-WRONG-POLICY%')",Integer.class)==2,30,"序列探针未隔离",out,stock);
            assertEquals("UNSUPPORTED_OUTBOUND_SCHEMA",stockDb.queryForObject("SELECT error_code FROM runtime_message_inbox WHERE payload LIKE '%SERIAL-DOWNGRADE%'",String.class));
            assertEquals("SERIAL_POLICY_MISMATCH",stockDb.queryForObject("SELECT error_code FROM runtime_message_inbox WHERE payload LIKE '%SERIAL-WRONG-POLICY%'",String.class));
            assertEquals(1,stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='PROCESS-SERIAL-PICK'",Integer.class));
            inventoryProcess=verifySerialShipmentProcesses(root,logs,inventoryProcess,inventory,outbound,kafka,inventoryPort,issuer,rsa,serialOrderPath,token);
        } finally { stop(inventoryProcess); stop(outboundProcess); jwks.stop(0); }
    }

    private static String json(Object value) { return RuntimeMessage.JSON.writeValueAsString(value); }

    /** 实际来源/库存/登记JAR和Kafka，直接触发真实XXL执行器；admin回调端为协议夹具。 */
    private Process verifySerialShipmentProcesses(Path root,Path logs,Process inventoryProcess,MySQLContainer inventory,
            MySQLContainer outbound,KafkaContainer kafka,int inventoryPort,String issuer,RSAKey rsa,String orderPath,String operator) throws Exception {
        Process current=inventoryProcess,registryProcess=null;boolean returned=false;
        var gateway=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var admin=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        admin.createContext("/",exchange -> {
            exchange.getRequestBody().readNBytes(65536);
            byte[] body="{\"code\":200,\"msg\":null,\"data\":null}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) {output.write(body);}
        });admin.start();
        try(var registryDb=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry")) {
            registryDb.start();int registryPort=port(),xxlPort=port();
            String serviceToken=token(issuer,rsa,"inventory-worker",List.of("serial.registry.write","serial.registry.read"));
            registryProcess=start(root,"serial-registry",registryDb,kafka,registryPort,issuer,logs,Map.of(
                    "WMS_SERIAL_JDBC_URL",registryDb.getJdbcUrl(),"WMS_SERIAL_DB_USER",registryDb.getUsername(),"WMS_SERIAL_DB_PASSWORD",registryDb.getPassword(),
                    "WMS_SERIAL_ALLOWED_SUBJECTS","inventory-worker","WMS_MESSAGING_ENABLED","false"));
            Process global=registryProcess;
            await(() -> ready(registryPort),60,"登记进程未就绪",global);
            try(var client=new com.lrj.wms.inventory.serial.SerialRegistryHttpClient(URI.create("http://127.0.0.1:"+registryPort),e -> serviceToken,Duration.ofMillis(1500))) {
                // 原收货/TC仍为本测试明确夹具；全球归属本身经过真实登记HTTP建立。
                for(int i=1;i<=5;i++) {
                    client.claim("ENT","SERIAL-SKU","PROCESS-SN-"+i,"WH","SERIAL-RECEIVE");
                    assertEquals(1L,((Number)client.activate("ENT","SERIAL-SKU","PROCESS-SN-"+i,"WH","SERIAL-RECEIVE").get("ownerEpoch")).longValue());
                }
            }
            var loseReply=new java.util.concurrent.atomic.AtomicBoolean(true);
            gateway.createContext("/",exchange -> {
                try {
                    byte[] input=exchange.getRequestBody().readNBytes(65536);
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+registryPort+exchange.getRequestURI())).timeout(Duration.ofSeconds(3));
                    for(String name:List.of("Authorization","Idempotency-Key","X-Wms-Enterprise-Id","Content-Type")) {
                        String value=exchange.getRequestHeaders().getFirst(name);if(value!=null) request.header(name,value);
                    }
                    var response=http.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(input)).build(),HttpResponse.BodyHandlers.ofByteArray());
                    boolean lost=response.statusCode()==200 && exchange.getRequestURI().getPath().endsWith("/shipments")
                            && new String(input,java.nio.charset.StandardCharsets.UTF_8).contains("PROCESS-SN-2") && loseReply.compareAndSet(true,false);
                    // 登记已经提交，网关故意返回503，确定性复现调用方不知道上次成功的窗口。
                    byte[] body=lost?"{\"code\":\"TEST_REPLY_LOST\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8):response.body();
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(lost?503:response.statusCode(),body.length);
                    try(var output=exchange.getResponseBody()) {output.write(body);}
                } catch(Exception failure) {exchange.close();}
            });gateway.start();
            Path tokens=Files.createTempDirectory(logs,"shipment-tokens-");
            Files.writeString(tokens.resolve(RuntimeMessage.hash("ENT")+".jwt"),serviceToken);
            String xxlToken=UUID.randomUUID().toString();
            Map<String,String> runtime=Map.of("WMS_SERIAL_CLIENT_ENABLED","true","WMS_SERIAL_CLIENT_BASE_URL","http://127.0.0.1:"+gateway.getAddress().getPort(),
                    "WMS_SERIAL_CLIENT_TOKEN_DIRECTORY",tokens.toString(),"WMS_SERIAL_CLIENT_ALLOW_HTTP","true",
                    "WMS_XXL_ADMIN_ADDRESSES","http://127.0.0.1:"+admin.getAddress().getPort(),"WMS_XXL_ACCESS_TOKEN",xxlToken,
                    "WMS_XXL_PORT",Integer.toString(xxlPort),"WMS_XXL_LOG_PATH",logs.resolve("shipment-xxl").toString());
            var stockDb=new JdbcTemplate(source(inventory));var outDb=new JdbcTemplate(source(outbound));var globalDb=new JdbcTemplate(source(registryDb));
            String choices=orderPath+"/shippable-serials?orderLineId=SERIAL-LINE&stagingLocationId=STAGE&lotId=NO_LOT";
            var available=get(choices,operator);assertEquals(200,available.statusCode(),available.body());assertEquals(2,RuntimeMessage.JSON.readTree(available.body()).path("items").size());
            assertEquals(403,get(choices,token(issuer,rsa,List.of("inventory.read"))).statusCode());
            assertEquals(201,post(orderPath+"/packings",operator,"SERIAL-PACK",json(Map.of("orderLineId","SERIAL-LINE","packageNo","SERIAL-PACK","qty","2"))).statusCode());
            String first=serialShipment("SERIAL-SHIP-PART-1","PROCESS-SN-1");
            var accepted=post(orderPath+"/shipments",operator,"PROCESS-SERIAL-SHIP-1",first);assertEquals(202,accepted.statusCode(),accepted.body());
            await(() -> applied(outDb,"PROCESS-SERIAL-SHIP-1"),30,"第一批序列SHIP未过账",current,global);
            assertEquals(1,RuntimeMessage.JSON.readTree(get(choices,operator).body()).path("items").size());
            stockDb.execute("ALTER TABLE serial_shipment_intent ADD CONSTRAINT fail_process_serial_ship CHECK(serial_id<>'PROCESS-SN-2')");
            String second=serialShipment("SERIAL-SHIP-PART-2","PROCESS-SN-2");
            accepted=post(orderPath+"/shipments",operator,"PROCESS-SERIAL-SHIP-2",second);assertEquals(202,accepted.statusCode(),accepted.body());
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM runtime_message_inbox WHERE source_service='wms-outbound' AND claim_epoch>0 AND payload LIKE '%PROCESS-SERIAL-SHIP-2%'",Integer.class)>0,30,"第二批SHIP未尝试",current);
            assertEquals(0,stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='PROCESS-SERIAL-SHIP-2'",Integer.class));
            assertEquals("AUTHORIZED",stockDb.queryForObject("SELECT state FROM local_serial WHERE serial_id='PROCESS-SN-2'",String.class));
            stop(current);current=null;stockDb.execute("ALTER TABLE serial_shipment_intent DROP CHECK fail_process_serial_ship");
            current=start(root,"inventory",inventory,kafka,inventoryPort,issuer,logs,runtime);
            await(() -> ready(inventoryPort) && executorReady(xxlPort,xxlToken),60,"库存或真实XXL执行器未就绪",current,global);
            await(() -> applied(outDb,"PROCESS-SERIAL-SHIP-2"),45,"第二批SHIP重启未恢复",current);
            triggerExecutor(xxlPort,xxlToken,1);
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent WHERE state='DONE'",Integer.class)==1
                    && stockDb.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent WHERE state='PENDING' AND attempts=1",Integer.class)==1,35,"未保存丢失登记回执的待恢复状态",current,global);
            assertEquals(2,globalDb.queryForObject("SELECT COUNT(*) FROM serial_shipment",Integer.class));
            assertEquals("ACTIVE",stockDb.queryForObject("SELECT registry_state FROM local_serial WHERE serial_id='PROCESS-SN-2'",String.class));
            stop(current);current=null;
            current=start(root,"inventory",inventory,kafka,inventoryPort,issuer,logs,runtime);
            await(() -> ready(inventoryPort) && executorReady(xxlPort,xxlToken),60,"发运恢复重启未就绪",current,global);
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent WHERE state='PENDING' AND next_attempt_at<=UTC_TIMESTAMP(6)",Integer.class)==1,10,"退避未到期",current);
            triggerExecutor(xxlPort,xxlToken,2);
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent WHERE state='DONE'",Integer.class)==2,30,"原发运回执未恢复",current,global);
            assertEquals(2,globalDb.queryForObject("SELECT COUNT(*) FROM serial_shipment",Integer.class));
            assertEquals(2,stockDb.queryForObject("SELECT COUNT(*) FROM local_serial WHERE state='SHIPPED' AND registry_state='SHIPPED'",Integer.class));
            assertEquals(2,stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id LIKE 'PROCESS-SERIAL-SHIP-%'",Integer.class));
            assertEquals(2,outDb.queryForObject("SELECT COUNT(*) FROM outbound_serial_pick WHERE shipment_posted_at IS NOT NULL",Integer.class));
            decimal(stockDb,"SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL-SKU' AND location_id='STAGE'","0");
            var replay=post(orderPath+"/shipments",operator,"PROCESS-SERIAL-SHIP-REPLAY",second);assertEquals(202,replay.statusCode(),replay.body());
            assertEquals("PROCESS-SERIAL-SHIP-2",RuntimeMessage.JSON.readTree(replay.body()).path("commandId").asString());
            var recoveries=get("http://127.0.0.1:"+inventoryPort+"/api/wms/v1/warehouses/WH/serial-recoveries?state=DONE",token(issuer,rsa,List.of("messaging.read")));
            assertEquals(200,recoveries.statusCode(),recoveries.body());
            assertEquals(2,RuntimeMessage.JSON.readTree(recoveries.body()).path("items").size());
            for(var row:RuntimeMessage.JSON.readTree(recoveries.body()).path("items")) assertEquals("SHIPMENT",row.path("kind").asString());
            returned=true;return current;
        } finally {if(!returned) stop(current);stop(registryProcess);gateway.stop(0);admin.stop(0);}
    }
    private static String serialShipment(String part,String serial) {
        return json(Map.of("orderLineId","SERIAL-LINE","qty","1","shipmentPartId",part,"stagingLocationId","STAGE","lotId","NO_LOT",
                "serialExecution",Map.of("schemaVersion",1,"identities",List.of(Map.of("serialId",serial,"ownerEpoch",1)))));
    }
    private boolean ready(int port) {
        try {return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
        catch(Exception failure) {return false;}
    }
    private boolean executorReady(int port,String token) {
        try {return RuntimeMessage.JSON.readTree(executorRequest(port,token,"beat","{}").body()).path("code").asInt()==200;}
        catch(Exception failure) {return false;}
    }
    private HttpResponse<String> executorRequest(int port,String token,String action,String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"+action)).timeout(Duration.ofSeconds(3))
                .header("XXL-JOB-ACCESS-TOKEN",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private void triggerExecutor(int port,String token,long logId) throws Exception {
        var body=new HashMap<String,Object>(Map.of("jobId",901,"executorHandler","serialTransferRecovery","executorParams","ENT,WH",
                "executorBlockStrategy","SERIAL_EXECUTION","executorTimeout",30,"logId",logId,"logDateTime",System.currentTimeMillis(),"glueType","BEAN",
                "broadcastIndex",0,"broadcastTotal",1));
        var result=executorRequest(port,token,"run",json(body));assertEquals(200,result.statusCode());
        assertEquals(200,RuntimeMessage.JSON.readTree(result.body()).path("code").asInt(),result.body());
    }
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
    private static void seedSerial(MySQLContainer inventory) {
        var config=new Configuration(new Environment("serial-pick-fixtures",new JdbcTransactionFactory(),source(inventory)));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        for(var mapper:List.of(MasterdataMapper.class,com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class,
                com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper.class,com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper.class,
                com.lrj.wms.inventory.serial.LocalSerialMapper.class)) config.addMapper(mapper);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(false)) {
            var clock=Clock.systemUTC();var now=java.sql.Timestamp.from(clock.instant());
            new MasterdataService(session,clock).createSku(SkuPolicy.create("SERIAL-SKU","ENT","SERIAL-SKU","序列商品","EA",0,false,true,false,1,"ACTIVE"),"SERIAL-UNIT");
            var bucket=com.lrj.wms.inventory.inventory.domain.StockBucketKey.of("ENT","WH","OWNER","SOURCE","SERIAL-SKU","NO_LOT","GOOD");
            var app=new com.lrj.wms.inventory.inventory.InventoryApplicationService(session,clock);
            app.receive("ENT","WH","SERIAL-RECEIVE","SERIAL-DOC","fixture",bucket,com.lrj.wms.inventory.inventory.domain.Quantity.parse("5",0));
            app.reserveTried("ENT","WH","SERIAL-TRY","SERIAL-DOC","fixture","ALLOC-SERIAL","ATT-SERIAL","fixture-serial-xid",1L,"ReservationTccAction",1L,"d".repeat(64),List.of(
                    new com.lrj.wms.inventory.inventory.ReservationLineInput(bucket,com.lrj.wms.inventory.inventory.domain.Quantity.parse("3",0),"SERIAL-LINE"),
                    new com.lrj.wms.inventory.inventory.ReservationLineInput(bucket,com.lrj.wms.inventory.inventory.domain.Quantity.parse("2",0),"SERIAL-OTHER-LINE")));
            app.confirmTried("ENT","WH","SERIAL-CONFIRM","SERIAL-DOC","fixture","ALLOC-SERIAL","ATT-SERIAL","fixture-serial-xid",1L,"ReservationTccAction");
            String balance=session.getMapper(com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class).lockBalanceByDimension("ENT","WH","OWNER","SOURCE","SERIAL-SKU","NO_LOT","GOOD").get("id").toString();
            var locals=session.getMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class);
            for(int n=1;n<=5;n++) {String sn="PROCESS-SN-"+n;locals.insertIgnore(UUID.randomUUID().toString(),"ENT","WH",sn,"SERIAL-SKU","NO_LOT",balance,"AUTHORIZED","SERIAL-RECEIVE","ACTIVE",null,now);locals.updateState("ENT","WH",sn,balance,"AUTHORIZED","ACTIVE",null,1L,now);}session.commit();
        }
    }
    private HttpResponse<String> get(String url,String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private Process start(Path root, String service, MySQLContainer db, KafkaContainer kafka, int port, String issuer, Path logs) throws Exception {
        return start(root,service,db,kafka,port,issuer,logs,Map.of());
    }
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs,Map<String,String> runtime) throws Exception {
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
        env.putAll(runtime);
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
        return token(issuer,rsa,"operator-process",scopes);
    }
    private static String token(String issuer,RSAKey rsa,String subject,List<String> scopes) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject(subject)
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).claim("enterprise_id", "ENT").claim("warehouses", List.of("WH"))
                .claim("scope", scopes).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("it").build(), claims); jwt.sign(new RSASSASigner(rsa)); return jwt.serialize();
    }
}
