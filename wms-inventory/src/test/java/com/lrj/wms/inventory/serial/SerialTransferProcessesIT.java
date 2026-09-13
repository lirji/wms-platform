package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.*;
import com.lrj.wms.inventory.masterdata.*;
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
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
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

/** 实际履约/库存/登记JAR、三库和Kafka；只有OIDC公钥与XXL管理端使用协议夹具。 */
class SerialTransferProcessesIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Test void publicPartialTransferRecoversOriginalReleaseAfterInventoryRestart() throws Exception {
        Path root=Path.of("..").toRealPath(),logs=Path.of("target","serial-transfer-processes").toAbsolutePath();Files.createDirectories(logs);
        var keysGenerator=KeyPairGenerator.getInstance("RSA");keysGenerator.initialize(2048);var keys=keysGenerator.generateKeyPair();
        var rsa=new RSAKey.Builder((RSAPublicKey)keys.getPublic()).privateKey((RSAPrivateKey)keys.getPrivate()).keyID("transfer-it").build();
        var jwks=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] publicKeys=new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jwks.createContext("/jwks",exchange->{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,publicKeys.length);try(var output=exchange.getResponseBody()){output.write(publicKeys);}});jwks.start();
        var admin=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        admin.createContext("/",exchange->{exchange.getRequestBody().readNBytes(65536);byte[] response="{\"code\":200,\"msg\":null,\"data\":null}".getBytes(java.nio.charset.StandardCharsets.US_ASCII);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);try(var output=exchange.getResponseBody()){output.write(response);}});admin.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort();Process stockProcess=null,orderProcess=null,registryProcess=null;
        try(var inventory=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory");
                var fulfillment=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment");
                var registry=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry");var kafka=new KafkaContainer("apache/kafka:3.8.0")) {
            inventory.start();fulfillment.start();registry.start();kafka.start();
            try(var client=AdminClient.create(new KafkaSettings(true,kafka.getBootstrapServers(),"wms.transferprocess","PLAINTEXT","","").connection())) {
                client.createTopics(List.of("inventory.events","inbound.commands","outbound.commands","transfer.commands","fulfillment.results","fulfillment.events","outbound.authorizations")
                        .stream().map(name->new NewTopic("wms.transferprocess."+name,1,(short)1)).toList()).all().get(20,TimeUnit.SECONDS);
            }
            int stockPort=port(),orderPort=port(),registryPort=port(),xxlPort=port();String xxlToken=UUID.randomUUID().toString();
            String operator=token(issuer,rsa,List.of("A","B"));Path tokens=Files.createTempDirectory(logs,"tokens-");Files.writeString(tokens.resolve(RuntimeMessage.hash("ENT")+".jwt"),operator);
            var stockRuntime=new HashMap<String,String>();stockRuntime.put("WMS_SERIAL_CLIENT_ENABLED","true");stockRuntime.put("WMS_SERIAL_CLIENT_BASE_URL","http://127.0.0.1:"+registryPort);
            stockRuntime.put("WMS_SERIAL_CLIENT_TOKEN_DIRECTORY",tokens.toString());stockRuntime.put("WMS_SERIAL_CLIENT_ALLOW_HTTP","true");
            stockRuntime.put("WMS_XXL_ADMIN_ADDRESSES","http://127.0.0.1:"+admin.getAddress().getPort());stockRuntime.put("WMS_XXL_ACCESS_TOKEN",xxlToken);stockRuntime.put("WMS_XXL_PORT",String.valueOf(xxlPort));stockRuntime.put("WMS_XXL_LOG_PATH",logs.resolve("xxl").toString());
            registryProcess=start(root,"serial-registry",registry,kafka,registryPort,issuer,logs,Map.of("WMS_SERIAL_ALLOWED_SUBJECTS","inventory-worker","WMS_MESSAGING_ENABLED","false"));
            stockProcess=start(root,"inventory",inventory,kafka,stockPort,issuer,logs,stockRuntime);
            orderProcess=start(root,"fulfillment",fulfillment,kafka,orderPort,issuer,logs,Map.of("WMS_SERIAL_TRANSFER_ENABLED","true"));
            await(()->ready(registryPort)&&ready(stockPort)&&ready(orderPort)&&executorReady(xxlPort,xxlToken),90,"三个服务或XXL未就绪，见"+logs,registryProcess,stockProcess,orderProcess);
            // 初始在库库存是隔离种子；登记调用真实HTTP，后续所有调拨均通过公开入口和真实消息。
            get("http://127.0.0.1:"+registryPort+"/internal/wms/v1/serial-identities?skuId=SKU&serial=NONE",operator,true);
            seed(inventory,registryPort,operator);
            var stockSql=new JdbcTemplate(source(inventory));var orderSql=new JdbcTemplate(source(fulfillment));var registrySql=new JdbcTemplate(source(registry));
            String base="http://127.0.0.1:"+orderPort+"/api/wms/v1";
            var response=post(base+"/transfers",operator,"TR",Map.of("transferId","TR","sourceWarehouseId","A","targetWarehouseId","B","lines",List.of(Map.of("lineId","LINE","skuId","SKU","plannedQty",2))));assertEquals(201,response.statusCode(),response.body());
            stockSql.execute("ALTER TABLE serial_release_intent ADD CONSTRAINT test_release_reply CHECK(state<>'DONE')");
            var issue=Map.of("lineId","LINE","postingContext",context("A"),"selection",selection("SN-1","SN-2"),"qty",2);
            response=post(base+"/transfers/TR/serial-issues",operator,"ISSUE",issue);assertEquals(202,response.statusCode(),response.body());String issueId=node(response).path("commandId").asString();
            String issueUrl=base+"/transfers/TR/serial-commands/"+issueId;
            assertEquals(403,get(issueUrl,token(issuer,rsa,List.of("C")),false).statusCode());
            await(()->stockSql.queryForObject("SELECT COUNT(*) FROM local_serial WHERE warehouse_id='A' AND state='SEALED'",Integer.class)==2,30,"库存未消费公开源命令",stockProcess,orderProcess);
            trigger(xxlPort,xxlToken,"A",1);
            await(()->stockSql.queryForObject("SELECT COUNT(*) FROM serial_release_intent WHERE attempts>0 AND state='PENDING'",Integer.class)==2,30,"源释放失败检查点未持久化",stockProcess);
            assertEquals(2,registrySql.queryForObject("SELECT COUNT(*) FROM serial_registry WHERE state='IN_TRANSIT'",Integer.class));
            assertEquals("PENDING",node(get(issueUrl,operator,false)).path("state").asString());
            assertEquals(0,orderSql.queryForObject("SELECT issued_qty FROM transfer_line WHERE id='LINE'",BigDecimal.class).signum());
            stop(stockProcess);stockProcess=null;stockSql.execute("ALTER TABLE serial_release_intent DROP CHECK test_release_reply");
            stockProcess=start(root,"inventory",inventory,kafka,stockPort,issuer,logs,stockRuntime);await(()->ready(stockPort)&&executorReady(xxlPort,xxlToken),60,"库存重启未就绪",stockProcess);
            trigger(xxlPort,xxlToken,"A",2);await(()->complete(issueUrl,operator),30,"原源命令未恢复完成",stockProcess,orderProcess);
            response=post(base+"/transfers/TR/serial-issues",operator,"ISSUE",issue);assertEquals(200,response.statusCode(),response.body());assertEquals(issueId,node(response).path("commandId").asString());
            assertEquals(2,stockSql.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE reason_code='TRANSFER_ISSUE'",Integer.class));
            for(int batch=1;batch<=2;batch++) {
                response=post(base+"/transfers/TR/receipt-authorizations",operator,"AUTH-"+batch,Map.of("lineId","LINE","quantity",1));assertEquals(201,response.statusCode(),response.body());var auth=node(response);
                var receipt=new HashMap<String,Object>();receipt.putAll(Map.of("transferId","TR","lineId","LINE","postingContext",context("B"),"selection",selection("SN-"+batch),"qty",1,"authorizationId",auth.path("authorizationId").asString(),"tokenVersion",auth.path("tokenVersion").longValue()));
                response=post(base+"/warehouses/B/serial-transfer-receipts",operator,"RECEIVE-"+batch,receipt);assertEquals(202,response.statusCode(),response.body());String id=node(response).path("commandId").asString();
                String receiptUrl=base+"/transfers/TR/serial-commands/"+id;int expected=batch;
                await(()->stockSql.queryForObject("SELECT COUNT(*) FROM local_serial WHERE warehouse_id='B'",Integer.class)==expected,30,"目的HOLD未入账",stockProcess,orderProcess);
                trigger(xxlPort,xxlToken,"B",10+batch);await(()->complete(receiptUrl,operator),30,"目的原登记回执未完成",stockProcess,orderProcess);
                response=post(base+"/warehouses/B/serial-transfer-receipts",operator,"RECEIVE-"+batch,receipt);assertEquals(200,response.statusCode(),response.body());assertEquals(id,node(response).path("commandId").asString());
                assertEquals(0,orderSql.queryForObject("SELECT received_qty FROM transfer_line WHERE id='LINE'",BigDecimal.class).compareTo(BigDecimal.valueOf(batch)));
            }
            assertEquals(2,registrySql.queryForObject("SELECT COUNT(*) FROM serial_registry WHERE state='ACTIVE' AND owner_warehouse_id='B' AND owner_epoch=2",Integer.class));
            assertEquals(0,stockSql.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='A'",BigDecimal.class).signum());
            assertEquals(0,stockSql.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='B'",BigDecimal.class).compareTo(BigDecimal.valueOf(2)));
        } finally {stop(stockProcess);stop(orderProcess);stop(registryProcess);jwks.stop(0);admin.stop(0);}
    }
    private static void seed(MySQLContainer db,int port,String token) {
        var config=new Configuration(new Environment("seed",new JdbcTransactionFactory(),source(db)));com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        for(Class<?> mapper:List.of(MasterdataMapper.class,InventoryMapper.class,OutboxMapper.class,CommandDedupMapper.class,LocalSerialMapper.class,SerialRecoveryMapper.class)) config.addMapper(mapper);
        try(var registry=new SerialRegistryHttpClient(URI.create("http://127.0.0.1:"+port),e->token,Duration.ofMillis(1500));var session=new SqlSessionFactoryBuilder().build(config).openSession(false)) {
            var master=new MasterdataService(session,Clock.systemUTC());master.createSku(SkuPolicy.create("SKU","ENT","SKU","序列商品","EA",0,false,true,false,1,"ACTIVE"),"UNIT");
            for(String w:List.of("A","B")) {master.createWarehouse(w,"ENT",w,w,"UTC");master.createLocation("LOC-"+w,"GATE-"+w,"ENT",w,"LOC-"+w,"ZONE","STORAGE",BigDecimal.valueOf(100),"EA");}
            for(String sn:List.of("SN-1","SN-2")) {
                var received=new SerialReceiptService(session,Clock.systemUTC(),registry).receiveHold("ENT","A","INITIAL-"+sn,"INITIAL","actor",sn,StockBucketKey.of("ENT","A","OWNER","LOC-A","SKU","NO_LOT","HOLD"));
                assertEquals("AUTHORIZED",received.get("state"));
            }
            session.commit();
        }
    }
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs,Map<String,String> runtime) throws Exception {
        Path jar=root.resolve("wms-"+service+"/target/wms-"+service+"-0.1.0-SNAPSHOT.jar");assertTrue(Files.isRegularFile(jar));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",jar.toString());var env=builder.environment();env.keySet().removeIf(key->key.startsWith("WMS_"));
        String prefix="serial-registry".equals(service)?"SERIAL":service.toUpperCase(Locale.ROOT);
        env.put("WMS_HTTP_PORT",String.valueOf(port));env.put("WMS_BIND_ADDRESS","127.0.0.1");env.put("WMS_"+prefix+"_JDBC_URL",db.getJdbcUrl());env.put("WMS_"+prefix+"_DB_USER",db.getUsername());env.put("WMS_"+prefix+"_DB_PASSWORD",db.getPassword());
        env.put("WMS_OIDC_ISSUER",issuer);env.put("WMS_OIDC_JWK_SET_URI",issuer+"/jwks");env.put("WMS_OIDC_CLIENT_ID","wms-platform");env.put("WMS_MESSAGING_ENABLED","true");env.put("WMS_MESSAGING_BOOTSTRAPSERVERS",kafka.getBootstrapServers());env.put("WMS_MESSAGING_TOPICPREFIX","wms.transferprocess");env.putAll(runtime);
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(service+".log").toFile())).start();
    }
    private static Map<String,Object> context(String w) {return Map.of("documentId","TR","ownerId","OWNER","skuId","SKU","baseUnit","EA","sourceLocationId","LOC-"+w,"lotId","NO_LOT","qualityCode","HOLD");}
    private static Map<String,Object> selection(String...sns) {return Map.of("schemaVersion",1,"identities",Arrays.stream(sns).map(sn->Map.of("serialId",sn,"ownerEpoch",1)).toList());}
    private HttpResponse<String> post(String url,String token,String key,Object body) throws Exception {return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(RuntimeMessage.JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> get(String url,String token,boolean internal) throws Exception {var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+token);if(internal) request.header("X-Wms-Enterprise-Id","ENT");return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());}
    private boolean complete(String url,String token) {try {var response=get(url,token,false);return response.statusCode()==200&&"COMPLETE".equals(node(response).path("state").asString());}catch(Exception pending){return false;}}
    private static tools.jackson.databind.JsonNode node(HttpResponse<String> response) {return RuntimeMessage.JSON.readTree(response.body());}
    private boolean ready(int port) {try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception pending){return false;}}
    private HttpResponse<String> executor(int port,String token,String action,Object body) throws Exception {return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"+action)).timeout(Duration.ofSeconds(3)).header("XXL-JOB-ACCESS-TOKEN",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(RuntimeMessage.JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private boolean executorReady(int port,String token) {try{return node(executor(port,token,"beat",Map.of())).path("code").asInt()==200;}catch(Exception pending){return false;}}
    private void trigger(int port,String token,String warehouse,long id) throws Exception {var body=new HashMap<String,Object>(Map.of("jobId",902,"executorHandler","serialTransferRecovery","executorParams","ENT,"+warehouse,"executorBlockStrategy","SERIAL_EXECUTION","executorTimeout",30,"logId",id,"logDateTime",System.currentTimeMillis(),"glueType","BEAN","broadcastIndex",0,"broadcastTotal",1));var response=executor(port,token,"run",body);assertEquals(200,response.statusCode(),response.body());assertEquals(200,node(response).path("code").asInt(),response.body());}
    private static String token(String issuer,RSAKey rsa,List<String> warehouses) throws Exception {var claims=new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject("inventory-worker").expirationTime(Date.from(Instant.now().plusSeconds(900))).claim("enterprise_id","ENT").claim("warehouses",warehouses).claim("scope",List.of("transfer.create","transfer.read","transfer.authorizeReceipt","transfer.receive","serial.registry.read","serial.registry.write")).build();var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("transfer-it").build(),claims);jwt.sign(new RSASSASigner(rsa));return jwt.serialize();}
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC"));source.setUser(db.getUsername());source.setPassword(db.getPassword());return source;}
    private static void await(BooleanSupplier done,int seconds,String message,Process... processes) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!done.getAsBoolean()){for(var process:processes)assertTrue(process.isAlive(),message);assertTrue(System.nanoTime()<deadline,message);Thread.sleep(250);}}
    private static int port() throws Exception {try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private static void stop(Process process) throws Exception {if(process==null)return;process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
}
