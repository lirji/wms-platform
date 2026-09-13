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
import java.time.temporal.ChronoUnit;
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

/** 三个真实JAR/三库/Kafka/XXL执行器验证水位；OIDC公钥和XXL管理端明确使用协议夹具。 */
class ReconciliationProcessesIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Test void missingReceiptPreventsExportAndInventoryRestartResumesOriginalWindow() throws Exception {
        Path root=Path.of("..").toRealPath(),logs=Path.of("target","reconciliation-processes").toAbsolutePath();Files.createDirectories(logs);
        requireCurrentRuntime(root);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
        var rsa=new RSAKey.Builder((RSAPublicKey)keys.getPublic()).privateKey((RSAPrivateKey)keys.getPrivate()).keyID("recon-it").build();
        var jwks=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] publicKeys=new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jwks.createContext("/jwks",exchange -> {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,publicKeys.length);try(var out=exchange.getResponseBody()) {out.write(publicKeys);}});jwks.start();
        var admin=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        admin.createContext("/",exchange -> {
            exchange.getRequestBody().readNBytes(65536);byte[] bytes="{\"code\":200,\"msg\":null,\"data\":null}".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()) {out.write(bytes);}
        });admin.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort();
        Process inProcess=null,outProcess=null,stockProcess=null;
        try(var inbound=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inbound");
                var outbound=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound");
                var inventory=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory");
                var kafka=new KafkaContainer("apache/kafka:3.8.0")) {
            inbound.start();outbound.start();inventory.start();kafka.start();
            var settings=new KafkaSettings(true,kafka.getBootstrapServers(),"wms.reconprocess","PLAINTEXT","","");
            try(var kafkaAdmin=AdminClient.create(settings.connection())) {
                kafkaAdmin.createTopics(List.of("inventory.events","inbound.commands","inbound.results","outbound.commands","outbound.results")
                        .stream().map(name -> new NewTopic("wms.reconprocess."+name,1,(short)1)).toList()).all().get(20,TimeUnit.SECONDS);
            }
            int inPort=port(),outPort=port(),stockPort=port(),xxlPort=port();String xxlToken=UUID.randomUUID().toString();
            Path tokens=Files.createTempDirectory(logs,"service-tokens-");
            Files.writeString(tokens.resolve(RuntimeMessage.hash("ENT")+".jwt"),token(issuer,rsa,"recon-worker",List.of("recon.evidence")));
            Map<String,String> stockRuntime=new HashMap<>();
            stockRuntime.put("WMS_RECONCILIATION_COLLECTOR_ENABLED","true");stockRuntime.put("WMS_RECONCILIATION_INBOUND_URL","http://127.0.0.1:"+inPort);
            stockRuntime.put("WMS_RECONCILIATION_OUTBOUND_URL","http://127.0.0.1:"+outPort);stockRuntime.put("WMS_RECONCILIATION_TOKEN_DIRECTORY",tokens.toString());stockRuntime.put("WMS_RECONCILIATION_ALLOW_HTTP","true");
            stockRuntime.put("WMS_XXL_ADMIN_ADDRESSES","http://127.0.0.1:"+admin.getAddress().getPort());stockRuntime.put("WMS_XXL_ACCESS_TOKEN",xxlToken);
            stockRuntime.put("WMS_XXL_PORT",String.valueOf(xxlPort));stockRuntime.put("WMS_XXL_LOG_PATH",logs.resolve("xxl").toString());
            inProcess=start(root,"inbound",inbound,kafka,inPort,issuer,logs,Map.of());
            outProcess=start(root,"outbound",outbound,kafka,outPort,issuer,logs,Map.of("WMS_MESSAGING_ENABLED","false"));
            stockProcess=start(root,"inventory",inventory,kafka,stockPort,issuer,logs,stockRuntime);
            await(() -> ready(inPort) && ready(outPort) && ready(stockPort) && executorReady(xxlPort,xxlToken),90,"三个进程未就绪，日志="+logs,inProcess,outProcess,stockProcess);
            var stockDb=new JdbcTemplate(source(inventory));var inDb=new JdbcTemplate(source(inbound));
            seed(inventory);
            // 库存确实提交，但来源T3最后状态暂不能提交；仅在隔离测试库注入该故障。
            inDb.execute("ALTER TABLE source_command ADD CONSTRAINT recon_hold_receipt CHECK(state<>'APPLIED')");
            String operator=token(issuer,rsa,"operator",List.of("inbound.create","inbound.read","inbound.receive","recon.export","recon.read"));
            String inboundBase="http://127.0.0.1:"+inPort+"/api/wms/v1/warehouses/WH";
            var response=post(inboundBase+"/inbound-orders",operator,"ORDER","{\"sourceSystem\":\"ERP\",\"externalNo\":\"EXT\",\"ownerId\":\"OWNER\",\"lines\":[{\"lineId\":\"LINE\",\"externalLineId\":\"EXT-LINE\",\"skuId\":\"SKU\",\"expectedQty\":\"3\",\"unit\":\"EA\"}]}");
            assertEquals(201,response.statusCode(),response.body());
            String receipt="{\"lineId\":\"LINE\",\"qty\":\"3\",\"receiptPartId\":\"PART\",\"locationId\":\"LOC\",\"lotId\":\"NO_LOT\"}";
            response=post(inboundBase+"/inbound-orders/ORDER/receipts",operator,"RECEIVE-CMD",receipt);assertEquals(202,response.statusCode(),response.body());
            await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='RECEIVE-CMD'",Integer.class)==1,45,"真实收货未过账",inProcess,stockProcess);
            String cutoff=Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
            String window="http://127.0.0.1:"+stockPort+"/api/wms/v1/warehouses/WH/reconciliation-windows/C-PROCESS";
            response=post(window,operator,"WINDOW",json(Map.of("cutoff",cutoff)));assertEquals(202,response.statusCode(),response.body());
            trigger(xxlPort,xxlToken,1);
            await(() -> "SOURCE_PENDING".equals(stockDb.queryForObject("SELECT collection_error FROM reconciliation_cutoff WHERE cutoff_id='C-PROCESS'",String.class)),20,"缺T3未保留等待状态",stockProcess);
            assertEquals(0,stockDb.queryForObject("SELECT evidence_version FROM reconciliation_cutoff WHERE cutoff_id='C-PROCESS'",Integer.class));
            response=post("http://127.0.0.1:"+stockPort+"/api/wms/v1/reconciliation-snapshots",operator,"FORGED",snapshot(cutoff,"FORGED","FORGED","FORGED"));
            assertEquals(400,response.statusCode(),response.body());assertTrue(response.body().contains("SOURCE_INCOMPLETE"));
            stop(stockProcess);stockProcess=null;
            inDb.execute("ALTER TABLE source_command DROP CHECK recon_hold_receipt");
            stockProcess=start(root,"inventory",inventory,kafka,stockPort,issuer,logs,stockRuntime);
            await(() -> ready(stockPort) && executorReady(xxlPort,xxlToken),75,"库存重启未恢复",stockProcess);
            await(() -> "APPLIED".equals(inDb.queryForObject("SELECT state FROM source_command WHERE command_id='RECEIVE-CMD'",String.class)),60,"来源原回执未恢复",inProcess,stockProcess);
            for(int n=2;n<=9;n++) {
                if(stockDb.queryForObject("SELECT evidence_version FROM reconciliation_cutoff WHERE cutoff_id='C-PROCESS'",Integer.class)==1) break;
                long before=stockDb.queryForObject("SELECT claim_epoch FROM reconciliation_cutoff WHERE cutoff_id='C-PROCESS'",Long.class);
                trigger(xxlPort,xxlToken,n);
                await(() -> stockDb.queryForObject("SELECT COUNT(*) FROM reconciliation_cutoff WHERE cutoff_id='C-PROCESS' AND claim_epoch>? AND collection_state<>'RUNNING'",Integer.class,before)==1,20,"检查点未推进",stockProcess);
                var state=stockDb.queryForMap("SELECT collection_state,collection_progress,collection_error,claim_epoch FROM reconciliation_cutoff WHERE cutoff_id='C-PROCESS'");
                Files.writeString(logs.resolve("window-step-"+n+".json"),json(state));
                if(state.get("collection_error")!=null) {
                    // 只记录本测试合成的原凭证；失败时先取得具体匹配证据，不盲等下一次领取。
                    Files.writeString(logs.resolve("posting-diagnostic.json"),json(stockDb.queryForList("SELECT command_id,action,source_execution_id,id,quantity,created_at FROM stock_posting")));
                    var original=get("http://127.0.0.1:"+inPort+"/internal/wms/v1/warehouses/WH/reconciliation-windows/C-PROCESS/facts?cutoff="+URLEncoder.encode(cutoff,java.nio.charset.StandardCharsets.UTF_8),token(issuer,rsa,"recon-worker",List.of("recon.evidence")));
                    Files.writeString(logs.resolve("source-diagnostic.json"),original.body());
                }
                assertNull(state.get("collection_error"),json(state));
            }
            response=get(window,operator);assertEquals(200,response.statusCode(),response.body());var proof=RuntimeMessage.JSON.readTree(response.body());
            assertTrue(proof.path("watermarksComplete").asBoolean(),response.body());
            assertEquals(1,stockDb.queryForObject("SELECT COUNT(*) FROM source_execution_fact WHERE fact_kind='PHYSICAL'",Integer.class));
            response=post("http://127.0.0.1:"+stockPort+"/api/wms/v1/reconciliation-snapshots",operator,"EXPORT",snapshot(cutoff,proof.path("sourceWatermark").asString(),proof.path("postingWatermark").asString(),proof.path("receiptWatermark").asString()));
            assertEquals(202,response.statusCode(),response.body());String snapshotId=RuntimeMessage.JSON.readTree(response.body()).path("snapshotJobId").asString();
            response=get("http://127.0.0.1:"+stockPort+"/api/wms/v1/reconciliation-snapshots/"+snapshotId+"?warehouseId=WH",operator);
            assertEquals(200,response.statusCode(),response.body());var exported=RuntimeMessage.JSON.readTree(response.body());
            assertEquals("COMPLETE",exported.path("state").asString());assertTrue(exported.path("parts").get(0).path("payload").asString().contains("\"quantity\":\"3\""));
            assertEquals(202,post(inboundBase+"/inbound-orders/ORDER/receipts",operator,"RECEIVE-CMD",receipt).statusCode());
            assertEquals(1,stockDb.queryForObject("SELECT COUNT(*) FROM stock_posting",Integer.class));
            assertEquals(0,new BigDecimal("3").compareTo(stockDb.queryForObject("SELECT on_hand_qty FROM stock_balance",BigDecimal.class)));
        } finally {stop(stockProcess);stop(inProcess);stop(outProcess);jwks.stop(0);admin.stop(0);}
    }
    private static String snapshot(String cutoff,String source,String posting,String receipt) {return json(Map.of("warehouseIds",List.of("WH"),"cutoffId","C-PROCESS","cutoff",cutoff,"sourceWatermark",source,"postingWatermark",posting,"receiptWatermark",receipt));}
    private static void seed(MySQLContainer db) {
        var config=new Configuration(new Environment("seed",new JdbcTransactionFactory(),source(db)));com.lrj.wms.runtime.db.DatabaseInstants.configure(config);config.addMapper(MasterdataMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(false)) {
            var masterdata=new MasterdataService(session,Clock.systemUTC());masterdata.createWarehouse("WH","ENT","WH","测试仓","UTC");
            masterdata.createLocation("LOC","GATE","ENT","WH","LOC","A","RECEIVING",new BigDecimal("100"),"EA");
            masterdata.createSku(SkuPolicy.create("SKU","ENT","SKU","测试商品","EA",0,false,false,false,1,"ACTIVE"),"UNIT");session.commit();
        }
    }
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs,Map<String,String> runtime) throws Exception {
        Path jar=root.resolve("wms-"+service+"/target/wms-"+service+"-0.1.0-SNAPSHOT.jar");assertTrue(Files.isRegularFile(jar));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",jar.toString());var env=builder.environment();
        env.keySet().removeIf(key -> key.startsWith("WMS_"));
        env.put("WMS_HTTP_PORT",String.valueOf(port));env.put("WMS_BIND_ADDRESS","127.0.0.1");
        env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_JDBC_URL",db.getJdbcUrl());env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_USER",db.getUsername());env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_PASSWORD",db.getPassword());
        env.put("WMS_OIDC_ISSUER",issuer);env.put("WMS_OIDC_JWK_SET_URI",issuer+"/jwks");env.put("WMS_OIDC_CLIENT_ID","wms-platform");
        env.put("WMS_MESSAGING_ENABLED","true");env.put("WMS_MESSAGING_RECOVERYENABLED","true");env.put("WMS_MESSAGING_BOOTSTRAPSERVERS",kafka.getBootstrapServers());env.put("WMS_MESSAGING_TOPICPREFIX","wms.reconprocess");
        env.put("WMS_RECONCILIATION_WINDOW_ENABLED","true");env.put("WMS_RECONCILIATION_ALLOWED_SUBJECTS","recon-worker");env.putAll(runtime);
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(service+".log").toFile())).start();
    }
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC"));source.setUser(db.getUsername());source.setPassword(db.getPassword());return source;}
    private HttpResponse<String> post(String url,String token,String key,String body) throws Exception {return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> get(String url,String token) throws Exception {return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private boolean ready(int port) {try {return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;} catch(Exception failure) {return false;}}
    private HttpResponse<String> executor(int port,String token,String action,String body) throws Exception {return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"+action)).timeout(Duration.ofSeconds(3)).header("XXL-JOB-ACCESS-TOKEN",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private boolean executorReady(int port,String token) {try {return RuntimeMessage.JSON.readTree(executor(port,token,"beat","{}").body()).path("code").asInt()==200;} catch(Exception failure) {return false;}}
    private void trigger(int port,String token,long id) throws Exception {
        var body=new HashMap<String,Object>(Map.of("jobId",902,"executorHandler","stockInternalReconcile","executorParams","ENT,WH","executorBlockStrategy","SERIAL_EXECUTION","executorTimeout",30,"logId",id,"logDateTime",System.currentTimeMillis(),"glueType","BEAN","broadcastIndex",0,"broadcastTotal",1));
        var result=executor(port,token,"run",json(body));assertEquals(200,result.statusCode());assertEquals(200,RuntimeMessage.JSON.readTree(result.body()).path("code").asInt(),result.body());
    }
    private static void await(BooleanSupplier done,int seconds,String message,Process... processes) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!done.getAsBoolean()) {for(var process:processes) assertTrue(process.isAlive(),message);assertTrue(System.nanoTime()<deadline,message);Thread.sleep(250);}}
    private static int port() throws Exception {try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) {return socket.getLocalPort();}}
    private static void stop(Process process) throws Exception {if(process==null) return;process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    private static String token(String issuer,RSAKey rsa,String subject,List<String> scopes) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject(subject).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("enterprise_id","ENT").claim("warehouses",List.of("WH")).claim("scope",scopes).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("recon-it").build(),claims);jwt.sign(new RSASSASigner(rsa));return jwt.serialize();
    }
    private static String json(Object value) {return RuntimeMessage.JSON.writeValueAsString(value);}
    /** 在启动昂贵的容器前证明三个可执行包嵌入的是本次运行库，避免旧产物造成假失败或假通过。 */
    private static void requireCurrentRuntime(Path root) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        String expected=HexFormat.of().formatHex(digest.digest(Files.readAllBytes(root.resolve("wms-runtime/target/wms-runtime-0.1.0-SNAPSHOT.jar"))));
        for(String service:List.of("inbound","outbound","inventory")) {
            try(var jar=new java.util.zip.ZipFile(root.resolve("wms-"+service+"/target/wms-"+service+"-0.1.0-SNAPSHOT.jar").toFile())) {
                var entry=jar.getEntry("BOOT-INF/lib/wms-runtime-0.1.0-SNAPSHOT.jar");assertNotNull(entry,service+"缺运行库");
                try(var input=jar.getInputStream(entry)) {assertEquals(expected,HexFormat.of().formatHex(digest.digest(input.readAllBytes())),service+"包内运行库过期，须从当前薄Jar重新打包");}
            }
        }
    }
}
