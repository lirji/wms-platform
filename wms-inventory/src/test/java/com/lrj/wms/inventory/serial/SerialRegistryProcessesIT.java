package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.*;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 独立登记可执行Jar、RSA/JWKS与两库，验证真实HTTP和本地恢复事务的崩溃窗口。 */
class SerialRegistryProcessesIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Test void lostRepliesAndLocalCommitFailureRecoverFromPersistedOriginalIntent() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys=generator.generateKeyPair();
        var rsa=new RSAKey.Builder((RSAPublicKey)keys.getPublic()).privateKey((RSAPrivateKey)keys.getPrivate()).keyID("it").build();
        var jwks=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] publicKeys=new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        jwks.createContext("/jwks",exchange -> { exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,publicKeys.length);
            try(var output=exchange.getResponseBody()) { output.write(publicKeys); } }); jwks.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort(); Process process=null;
        Path log=Path.of("target","serial-registry-process.log").toAbsolutePath();
        try(var registryDb=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry");
                var inventoryDb=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")) {
            registryDb.start(); inventoryDb.start(); int port;
            try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
            Path jar=Path.of("../wms-serial-registry/target/wms-serial-registry-0.1.0-SNAPSHOT.jar").toRealPath();
            var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",jar.toString());
            var env=builder.environment(); env.put("WMS_HTTP_PORT",Integer.toString(port)); env.put("WMS_BIND_ADDRESS","127.0.0.1");
            env.put("WMS_SERIAL_JDBC_URL",registryDb.getJdbcUrl()); env.put("WMS_SERIAL_DB_USER",registryDb.getUsername()); env.put("WMS_SERIAL_DB_PASSWORD",registryDb.getPassword());
            env.put("WMS_SERIAL_ALLOWED_SUBJECTS","inventory-worker"); env.put("WMS_OIDC_ISSUER",issuer); env.put("WMS_OIDC_JWK_SET_URI",issuer+"/jwks"); env.put("WMS_OIDC_CLIENT_ID","wms-platform");
            process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            String base="http://127.0.0.1:"+port;
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos(); boolean ready=false;
            while(System.nanoTime()<deadline && process.isAlive()) {
                try { ready=http.send(HttpRequest.newBuilder(URI.create(base+"/actuator/health/readiness")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200; }
                catch(Exception starting) { /* 启动有界等待，失败日志保留在target。 */ }
                if(ready) break; Thread.sleep(100);
            }
            assertTrue(ready,"登记进程未就绪，日志="+log);
            String token=token(issuer,rsa);
            // 预热真实验签/JWKS，避免把首次类加载延迟误判为正常调用预算。
            assertEquals(404,http.send(HttpRequest.newBuilder(URI.create(base+"/internal/wms/v1/serial-identities?skuId=SKU&serial=NONE"))
                    .header("Authorization","Bearer "+token).header("X-Wms-Enterprise-Id","ENT").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var source=source(inventoryDb); Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var config=new Configuration(new Environment("inventory",new JdbcTransactionFactory(),source));
            com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
            config.addMapper(com.lrj.wms.inventory.recon.ReconciliationMapper.class); config.addMapper(MasterdataMapper.class); config.addMapper(InventoryMapper.class); config.addMapper(OutboxMapper.class);
            config.addMapper(CommandDedupMapper.class); config.addMapper(LocalSerialMapper.class); config.addMapper(SerialReleaseMapper.class); config.addMapper(SerialRecoveryMapper.class);
            config.addMapper(SerialReceiptBatchMapper.class); config.addMapper(StockCommandMapper.class);
            config.addMapper(com.lrj.wms.inventory.quality.ReceiptQualityStockMapper.class);
            config.addMapper(com.lrj.wms.inventory.effect.infrastructure.EffectMapper.class);
            var sessions=new SqlSessionFactoryBuilder().build(config); var jdbc=new JdbcTemplate(source); var registrySql=new JdbcTemplate(source(registryDb));
            Instant now=Instant.now(); Clock clock=Clock.fixed(now,ZoneOffset.UTC);
            try(var session=sessions.openSession(false)) {
                var master=new MasterdataService(session,clock);
                master.createSku(com.lrj.wms.inventory.masterdata.domain.SkuPolicy.create("SKU","ENT","SKU","序列号商品","EA",0,false,true,false,1,"ACTIVE"),"SKU-UNIT");
                for(String warehouse:List.of("A","B")) {
                    master.createWarehouse(warehouse,"ENT",warehouse,warehouse,"UTC");
                    master.createLocation("LOC-"+warehouse,"GATE-"+warehouse,"ENT",warehouse,warehouse,"A","STORAGE",new BigDecimal("100"),"EA");
                } session.commit();
            }
            try(var unavailable=new SerialRegistryHttpClient(URI.create(base),e -> null,Duration.ofMillis(1500));
                    var actual=new SerialRegistryHttpClient(URI.create(base),e -> token,Duration.ofMillis(1500))) {
                try(var session=sessions.openSession(false)) {
                    new SerialReceiptService(session,clock,unavailable).receiveHold("ENT","A","RECEIPT","DOC","actor","SN",bucket("A")); session.commit();
                }
                assertEquals("EXCEPTION",state(jdbc,"A")); assertQuantity(jdbc,"A",1); assertSerialCount(sessions,"A",1);
                var loseActivation=new AtomicBoolean(true);
                SerialRegistryPort droppedReply=new SerialRegistryPort() {
                    public Map<String,Object> claim(String e,String sku,String serial,String wh,String op) { return actual.claim(e,sku,serial,wh,op); }
                    public Map<String,Object> activate(String e,String sku,String serial,String wh,String op) {
                        var result=actual.activate(e,sku,serial,wh,op);
                        if(loseActivation.getAndSet(false)) throw new SerialRegistryUnavailableException("注入：远端已激活但回执丢失"); return result;
                    }
                    public Map<String,Object> get(String e,String sku,String serial) { return actual.get(e,sku,serial); }
                };
                assertEquals(1,new SerialRecoveryService(sessions,clock,droppedReply,actual).execute("ENT","A").failed());
                assertEquals("ACTIVE",registrySql.queryForObject("SELECT state FROM serial_registry WHERE normalized_serial='SN'",String.class));
                assertEquals("EXCEPTION",state(jdbc,"A")); assertQuantity(jdbc,"A",1);
                jdbc.execute("ALTER TABLE serial_recovery_intent ADD CONSTRAINT fail_done CHECK (state <> 'DONE')");
                assertEquals(1,new SerialRecoveryService(sessions,at(now,60),actual,actual).execute("ENT","A").failed());
                assertEquals("EXCEPTION",state(jdbc,"A"));
                jdbc.execute("ALTER TABLE serial_recovery_intent DROP CHECK fail_done");
                // 模拟进程重启：新执行器仅依赖持久化原操作及上下文。
                assertEquals(1,new SerialRecoveryService(sessions,at(now,120),actual,actual).execute("ENT","A").completed(), () -> jdbc.queryForList("SELECT state,last_error,attempts FROM serial_recovery_intent").toString());
                assertEquals("AUTHORIZED",state(jdbc,"A")); assertQuantity(jdbc,"A",1);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='RECEIPT'",Integer.class));
                assertEquals(2,registrySql.queryForObject("SELECT COUNT(*) FROM serial_http_command",Integer.class));
                long epoch=registrySql.queryForObject("SELECT owner_epoch FROM serial_registry WHERE normalized_serial='SN'",Long.class);
                post(base,token,"transfer-preparations",Map.of("warehouseId","A","targetWarehouseId","B","skuId","SKU","serial","SN","transferId","TRANSFER","operationId","PREPARE","expectedEpoch",epoch));
                jdbc.execute("ALTER TABLE serial_release_intent ADD CONSTRAINT fail_release_fact CHECK (serial_id<>'SN')");
                try(var session=sessions.openSession(false)) {
                    assertThrows(RuntimeException.class,() -> new SerialTransferLocalService(session,at(now,180),unavailable).sealSource("ENT","A","SN","TRANSFER",epoch,"RELEASE"));
                    session.rollback();
                }
                assertEquals("AUTHORIZED",state(jdbc,"A"));assertQuantity(jdbc,"A",1);
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='RELEASE'",Integer.class));
                jdbc.execute("ALTER TABLE serial_release_intent DROP CHECK fail_release_fact");
                try(var session=sessions.openSession(false)) {
                    var transfers=new SerialTransferLocalService(session,at(now,180),unavailable);
                    transfers.sealSource("ENT","A","SN","TRANSFER",epoch,"RELEASE");
                    transfers.stageDestination("ENT","B","DEST","DOC","actor","SN",bucket("B"),"TRANSFER",epoch); session.commit();
                }
                assertQuantity(jdbc,"A",0); assertQuantity(jdbc,"B",1); assertSerialCount(sessions,"A",0); assertSerialCount(sessions,"B",1);
                assertEquals(1,new SerialRecoveryService(sessions,at(now,240),actual,actual).execute("ENT","B").failed());
                assertEquals("HOLD_RECEIVED",state(jdbc,"B"));
                SerialReleaseRegistryPort lostRelease=(e,sku,sn,tr,wh,ref,from) -> {
                    actual.release(e,sku,sn,tr,wh,ref,from);
                    throw new SerialRegistryUnavailableException("注入：原释放已登记但源仓丢失回执");
                };
                assertEquals(1,new SerialReleaseRecoveryService(sessions,at(now,240),lostRelease).execute("ENT","A").failed());
                assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM serial_release_intent",String.class));
                assertEquals("IN_TRANSIT",registrySql.queryForObject("SELECT state FROM serial_registry WHERE normalized_serial='SN'",String.class));
                Thread.sleep(1100);
                SerialTransferRegistryPort droppedConfirmation=new SerialTransferRegistryPort() {
                    public Map<String,Object> startReceiving(String e,String sku,String serial,String tr,String wh,String ref,long from) { return actual.startReceiving(e,sku,serial,tr,wh,ref,from); }
                    public Map<String,Object> confirmDestination(String e,String sku,String serial,String tr,String wh,String ref) {
                        actual.confirmDestination(e,sku,serial,tr,wh,ref); throw new SerialRegistryUnavailableException("注入：归属已切换但回执丢失");
                    }
                };
                assertEquals(1,new SerialRecoveryService(sessions,at(now,300),actual,droppedConfirmation).execute("ENT","B").failed());
                assertEquals("HOLD_RECEIVED",state(jdbc,"B"));
                assertEquals("B",registrySql.queryForObject("SELECT owner_warehouse_id FROM serial_registry WHERE normalized_serial='SN'",String.class));
                // 让有界配额自然补充；不重建客户端绕过实际限流。
                Thread.sleep(1100);
                assertEquals(1,new SerialRecoveryService(sessions,at(now,360),actual,actual).execute("ENT","B").completed());
                assertEquals("AUTHORIZED",state(jdbc,"B")); assertEquals("SEALED",state(jdbc,"A"));
                assertEquals(epoch+1,jdbc.queryForObject("SELECT owner_epoch FROM local_serial WHERE warehouse_id='B'",Long.class));
                assertQuantity(jdbc,"A",0); assertQuantity(jdbc,"B",1);
                try(var session=sessions.openSession(false)) {
                    assertThrows(com.lrj.wms.inventory.inventory.InventoryException.class,() -> new SerialTransferLocalService(session,clock,actual)
                            .receiveDestination("ENT","B","DEST","DOC","actor","SN",bucket("B"),"TRANSFER",epoch+1));
                    session.rollback();
                }
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM serial_recovery_intent WHERE state='DONE'",Integer.class));
                // 新一轮转移已经开始，旧释放重放只能确认历史事实，不能恢复旧仓归属。
                post(base,token,"transfer-preparations",Map.of("warehouseId","B","targetWarehouseId","A","skuId","SKU","serial","SN","transferId","TRANSFER-2","operationId","PREPARE-2","expectedEpoch",epoch+1));
                Thread.sleep(1100);
                jdbc.execute("ALTER TABLE serial_release_intent ADD CONSTRAINT fail_release_done CHECK(state<>'DONE')");
                assertEquals(1,new SerialReleaseRecoveryService(sessions,at(now,380),actual).execute("ENT","A").failed());
                assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM serial_release_intent",String.class));
                jdbc.execute("ALTER TABLE serial_release_intent DROP CHECK fail_release_done");
                assertEquals(1,new SerialReleaseRecoveryService(sessions,at(now,390),actual).execute("ENT","A").completed());
                assertEquals("TRANSFER-2",registrySql.queryForObject("SELECT transfer_id FROM serial_registry WHERE normalized_serial='SN'",String.class));
                assertEquals("B",registrySql.queryForObject("SELECT owner_warehouse_id FROM serial_registry WHERE normalized_serial='SN'",String.class));
                assertEquals("SEALED",state(jdbc,"A"));assertQuantity(jdbc,"A",0);
                try(var session=sessions.openSession(false)) {
                    var transfers=new SerialTransferLocalService(session,clock,actual);
                    assertEquals("SEALED",transfers.sealSource("ENT","A","SN","TRANSFER",epoch,"RELEASE").get("state"));
                    assertThrows(com.lrj.wms.inventory.inventory.InventoryException.class,() -> transfers.sealSource("ENT","A","SN","TRANSFER",epoch+1,"RELEASE"));session.rollback();
                }
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='RELEASE'",Integer.class));
                assertEquals(1,registrySql.queryForObject("SELECT COUNT(*) FROM serial_http_command WHERE action='SOURCE_RELEASE'",Integer.class));

                // 多个身份共享原收货命令，逐身份真实HTTP登记不能因操作ID相同而互相覆盖或再次加量。
                var observation=new com.lrj.wms.contract.messaging.SerialReceiptObservation(1,List.of("BATCH-1","BATCH-2"));
                var context=new com.lrj.wms.contract.messaging.StockPostingContext("BATCH-DOC","OWNER","SKU","EA","LOC-A",null,"NO_LOT","HOLD",null,null);
                try(var session=sessions.openSession(false)) {
                    new SerialReceiptBatchService(session,clock).receive("ENT","A","BATCH-RECEIPT","BATCH-DOC","PART","LINE","actor","EXEC",
                            context,Quantity.parse("2",0),null,observation);session.commit();
                }
                Thread.sleep(1100);
                assertEquals(2,new SerialRecoveryService(sessions,at(now,400),actual,actual).execute("ENT","A").completed());
                assertEquals(2,registrySql.queryForObject("SELECT COUNT(*) FROM serial_registry WHERE normalized_serial IN ('BATCH-1','BATCH-2') AND state='ACTIVE' AND owner_warehouse_id='A'",Integer.class));
                assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE receipt_operation_id='BATCH-RECEIPT' AND state='AUTHORIZED' AND registry_state='ACTIVE'",Integer.class));
                assertEquals(0,new SerialRecoveryService(sessions,at(now,410),actual,actual).execute("ENT","A").completed());
                try(var session=sessions.openSession(false)) {
                    new SerialReceiptBatchService(session,clock).receive("ENT","A","BATCH-RECEIPT","BATCH-DOC","PART","LINE","actor","EXEC",
                            context,Quantity.parse("2",0),null,observation);session.commit();
                }
                assertQuantity(jdbc,"A",2);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='BATCH-RECEIPT'",Integer.class));
                try(var session=sessions.openSession(false)) {
                    var decision=new com.lrj.wms.contract.messaging.ReceiptQualityDecision("BATCH-RECEIPT","BATCH-Q",1,BigDecimal.ONE,BigDecimal.ONE);
                    var quality=new com.lrj.wms.contract.messaging.SerialQualityObservation(1,List.of("BATCH-1"),List.of("BATCH-2"));
                    new com.lrj.wms.inventory.inventory.StockCommandService(session,clock).applyQuality("ENT","A","BATCH-Q","LINE","BATCH-DOC","actor","EXEC-Q",bucket("A"),decision,quality);
                    session.commit();
                }
                assertEquals(List.of("GOOD","REJECTED"),jdbc.queryForList("SELECT b.quality_code FROM local_serial s JOIN stock_balance b ON b.id=s.balance_id WHERE s.receipt_operation_id='BATCH-RECEIPT' ORDER BY s.serial_id",String.class));
                try(var session=sessions.openSession(false)) {
                    new MasterdataService(session,clock).createLocation("PUT-STORAGE","PUT-GATE","ENT","A","PUT-STORAGE","A","STORAGE",new BigDecimal("100"),"EA");
                    var from=StockBucketKey.of("ENT","A","OWNER","LOC-A","SKU","NO_LOT","GOOD");
                    var to=StockBucketKey.of("ENT","A","OWNER","PUT-STORAGE","SKU","NO_LOT","GOOD");
                    new com.lrj.wms.inventory.inventory.StockCommandService(session,clock).applyPutaway("ENT","A","BATCH-PUT","BATCH-DOC","TASK","LINE","BATCH-DOC","actor","EXEC-PUT","BATCH-RECEIPT",from,to,Quantity.parse("1",0),
                            new com.lrj.wms.contract.messaging.SerialStockSelection(1,List.of("BATCH-1")));session.commit();
                }
                assertEquals("PUT-STORAGE",jdbc.queryForObject("SELECT b.location_id FROM local_serial s JOIN stock_balance b ON b.id=s.balance_id WHERE s.warehouse_id='A' AND s.serial_id='BATCH-1'",String.class));
                // 迁移停写后的旧进程不能领取或更新恢复状态，远端也不再被调用。
                jdbc.update("INSERT INTO warehouse_route(id,enterprise_id,warehouse_id,cell_id,target_cell_id,route_epoch,state,version,created_at,updated_at) VALUES('ROUTE-A','ENT','A','CELL-A','CELL-B',1,'QUIESCING',0,?,?)",java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
                var stopped=assertThrows(com.lrj.wms.inventory.inventory.InventoryException.class,() -> new SerialRecoveryService(sessions,at(now,420),actual,actual).execute("ENT","A"));
                assertEquals("STALE_ROUTE",stopped.code());
            }
            process.destroy(); assertTrue(process.waitFor(15,TimeUnit.SECONDS)); process=null;
        } finally { if(process!=null) { process.destroy(); if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } } jwks.stop(0); }
    }
    private void post(String base,String token,String action,Map<String,Object> body) throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/internal/wms/v1/serial-identities/"+action)).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+token).header("X-Wms-Enterprise-Id","ENT").header("Idempotency-Key",SerialRegistryHttpClient.digest(action+RuntimeMessage.JSON.writeValueAsString(new TreeMap<>(body)))).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(RuntimeMessage.JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
    }
    private static String token(String issuer,RSAKey rsa) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject("inventory-worker").expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("enterprise_id","ENT").claim("warehouses",List.of("A","B")).claim("scope",List.of("serial.registry.read","serial.registry.write")).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("it").build(),claims); jwt.sign(new RSASSASigner(rsa)); return jwt.serialize();
    }
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {
        var source=new com.mysql.cj.jdbc.MysqlDataSource(); source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC")); source.setUser(db.getUsername()); source.setPassword(db.getPassword()); return source;
    }
    private static StockBucketKey bucket(String wh) { return StockBucketKey.of("ENT",wh,"OWNER","LOC-"+wh,"SKU","NO_LOT","HOLD"); }
    private static void assertSerialCount(SqlSessionFactory sessions,String wh,int count) {
        try(var session=sessions.openSession()) {
            var rows=session.getMapper(com.lrj.wms.inventory.recon.ReconciliationMapper.class).balancePage("ENT",wh,java.sql.Timestamp.from(Instant.now().plusSeconds(1000)),null,10);
            assertEquals(count,((Number)rows.getFirst().get("serial_count")).intValue());
        }
    }
    private static Clock at(Instant now,int seconds) { return Clock.fixed(now.plusSeconds(seconds),ZoneOffset.UTC); }
    private static String state(JdbcTemplate jdbc,String wh) { return jdbc.queryForObject("SELECT state FROM local_serial WHERE warehouse_id=? AND serial_id='SN'",String.class,wh); }
    private static void assertQuantity(JdbcTemplate jdbc,String wh,int qty) { assertEquals(0,jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE warehouse_id=?",BigDecimal.class,wh).compareTo(BigDecimal.valueOf(qty))); }
}
