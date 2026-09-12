package com.lrj.wms.inventory;

import com.lrj.wms.contract.tcc.*;
import com.lrj.wms.fulfillment.SeataTmDriver;
import com.lrj.wms.fulfillment.TcEvidenceScope;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import com.lrj.wms.inventory.inventory.*;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.masterdata.*;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.github.dockerjava.api.model.*;
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
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 官方TM+真实TC+两个库存Jar验证原生RM；不以此替代履约自动执行器的业务验收。 */
class RuntimeRmProcessesIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Test void twoWarehouseCallbacksRecoverOriginalBranchesAfterProcessRestart() throws Exception {
        Path root=Path.of("..").toRealPath(),logs=Path.of("target/runtime-rm-processes").toAbsolutePath();Files.createDirectories(logs);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
        var rsa=new RSAKey.Builder((RSAPublicKey)keys.getPublic()).privateKey((RSAPrivateKey)keys.getPrivate()).keyID("rm-it").build();
        var jwks=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        jwks.createContext("/jwks",exchange->{byte[] body=new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}});jwks.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort();Process processA=null,processB=null;
        try(var tcDb=new MySQLContainer("mysql:8.4.11").withDatabaseName("seata").withUsername("tc").withPassword(UUID.randomUUID().toString());
            var a=new MySQLContainer("mysql:8.4.11").withDatabaseName("inventory_a").withPassword(UUID.randomUUID().toString());
            var b=new MySQLContainer("mysql:8.4.11").withDatabaseName("inventory_b").withPassword(UUID.randomUUID().toString())) {
            tcDb.start();a.start();b.start();
            var tcAdmin=source(tcDb);tcAdmin.setUser("root");
            Flyway.configure().dataSource(tcAdmin).locations("filesystem:"+root.resolve("wms-test-support/src/test/resources/db/tc-probe")).load().migrate();
            var tcSql=new JdbcTemplate(source(tcDb));int tcPort=port();
            try(var tc=new GenericContainer<>("apache/seata-server:2.6.0")
                    .withEnv("SEATA_IP","127.0.0.1").withEnv("SEATA_PORT",String.valueOf(tcPort)).withEnv("SEATA_SERVER_RETRY_DEAD_THRESHOLD","1000")
                    .withEnv("STORE_MODE","db").withEnv("JAVA_OPTS","-Xms128m -Xmx256m")
                    .withEnv("SEATA_STORE_DB_DATASOURCE","druid").withEnv("SEATA_STORE_DB_DB_TYPE","mysql")
                    .withEnv("SEATA_STORE_DB_DRIVER_CLASS_NAME","com.mysql.cj.jdbc.Driver")
                    .withEnv("SEATA_STORE_DB_URL","jdbc:mysql://"+tcDb.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress()+":3306/seata?allowPublicKeyRetrieval=true&useSSL=false")
                    .withEnv("SEATA_STORE_DB_USER",tcDb.getUsername()).withEnv("SEATA_STORE_DB_PASSWORD",tcDb.getPassword())
                    .withExposedPorts(tcPort).withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",tcPort),new ExposedPort(tcPort))))
                    .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3))) {
                tc.start();int portA=port(),portB=port();
                String advertised=tc.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress()+":"+tcPort;
                processA=start(root,a,"A",portA,tcPort,advertised,issuer,logs);processB=start(root,b,"B",portB,tcPort,advertised,issuer,logs);
                await(()->healthy(portA)&&healthy(portB),75,"RM未就绪，日志="+logs,processA,processB);
                seed(a,"A");seed(b,"B");var sqlA=new JdbcTemplate(source(a));var sqlB=new JdbcTemplate(source(b));
                String token=token(issuer,rsa,"wms-fulfillment",List.of("A","B"),"inventory.tcc.try");
                try(var tm=new SeataTmDriver(new TcEvidenceScope("rm-it","wms-fulfillment","wms_rm_group"),List.of("127.0.0.1:"+tcPort),null,null)) {
                    String xid=tm.begin("native-rm-commit",60000);
                    assertEquals(403,post(portA,"A",xid,request("A","COMMIT",2),token(issuer,rsa,"operator",List.of("A"),"inventory.tcc.try")).statusCode());
                    assertEquals(403,post(portA,"A",xid,request("A","COMMIT",2),token(issuer,rsa,"wms-fulfillment",List.of("B"),"inventory.tcc.try")).statusCode());
                    assertEquals(0,count(tcSql,"SELECT COUNT(*) FROM branch_table"));
                    String valid=RuntimeMessage.JSON.writeValueAsString(request("A","BAD-VERSION",1));
                    assertEquals(400,post(portA,"A",xid,valid.replace("\"schemaVersion\":1","\"schemaVersion\":4294967297"),token).statusCode());
                    assertEquals(400,post(portA,"A",xid,valid.replace("\"schemaVersion\":1","\"schemaVersion\":1.5"),token).statusCode());
                    assertEquals(0,count(sqlA,"SELECT COUNT(*) FROM inventory_tcc_intent WHERE attempt_id='BAD-VERSION'"));
                    assertEquals(409,post(portA,"A","127.0.0.1:1:123",request("A","WRONG-TC",1),token).statusCode());
                    assertEquals(0,count(sqlA,"SELECT COUNT(*) FROM inventory_tcc_intent WHERE attempt_id='WRONG-TC'"));
                    var ra=ok(post(portA,"A",xid,request("A","COMMIT",2),token));
                    var rb=ok(post(portB,"B",xid,request("B","COMMIT",3),token));
                    assertNotEquals(ra.actionName(),rb.actionName());assertEquals(ra,ok(post(portA,"A",xid,request("A","COMMIT",2),token)));
                    assertEquals(2,count(tcSql,"SELECT COUNT(*) FROM branch_table WHERE xid='"+xid+"'"));
                    assertEquals(409,post(portA,"A",xid,request("A","COMMIT",4),token).statusCode());
                    stop(processB);processB=null;
                    try {tm.commit(xid);} catch(com.lrj.wms.fulfillment.FulfillmentException unknown) {
                        assertEquals("TC_COMMIT_UNKNOWN",unknown.code(),"只允许真实RPC结果未知，后续必须证明原事务恢复");
                    }
                    assertNull(org.apache.seata.core.context.RootContext.getXID());
                    await(()->"CONFIRMED".equals(state(sqlA,"COMMIT")),30,"A未收到真实Confirm",processA);
                    assertEquals("TRIED",state(sqlB,"COMMIT"));
                    processB=start(root,b,"B",portB,tcPort,advertised,issuer,logs);
                    await(()->"CONFIRMED".equals(state(sqlB,"COMMIT")),45,"B重启未恢复原TC分支",processA,processB);
                    assertEquals(0,count(sqlA,"SELECT COUNT(*) FROM tcc_fence_log WHERE branch_id="+rb.branchId()));
                    assertEquals(1,count(sqlA,"SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'"));
                    assertEquals(1,count(sqlB,"SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'"));
                    assertEquals(rb.branchId(),sqlB.queryForObject("SELECT branch_id FROM inventory_tcc_intent WHERE attempt_id='COMMIT'",Long.class));
                    assertEquals("CONFIRMED",ok(post(portB,"B",xid,request("B","COMMIT",3),token)).state());
                    String rollback=tm.begin("native-rm-rollback",60000);
                    ok(post(portA,"A",rollback,request("A","ROLLBACK",1),token));
                    ok(post(portB,"B",rollback,request("B","ROLLBACK",1),token));
                    tm.rollback(rollback);
                    await(()->"CANCELLED".equals(state(sqlA,"ROLLBACK"))&&"CANCELLED".equals(state(sqlB,"ROLLBACK")),30,"真实Cancel未收敛",processA,processB);
                    assertEquals(2,sqlA.queryForObject("SELECT reserved_qty FROM stock_balance",BigDecimal.class).intValueExact());
                    assertEquals(3,sqlB.queryForObject("SELECT reserved_qty FROM stock_balance",BigDecimal.class).intValueExact());
                    assertEquals(409,post(portA,"A",rollback,request("A","ROLLBACK",1),token).statusCode());
                    assertEquals(2,count(sqlA,"SELECT COUNT(*) FROM tcc_fence_log"));assertEquals(2,count(sqlB,"SELECT COUNT(*) FROM tcc_fence_log"));
                }
                tc.stop();
                await(()->!healthy(portA)&&!healthy(portB),20,"TC断连仍错误就绪",processA,processB);
                tc.start();
                await(()->healthy(portA)&&healthy(portB),40,"TC恢复后RM未重新就绪",processA,processB);
            }
        } finally {stop(processA);stop(processB);jwks.stop(0);}
    }
    private Process start(Path root,MySQLContainer db,String cell,int port,int tcPort,String advertised,String issuer,Path logs)throws Exception {
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx192m","-jar",root.resolve("wms-inventory/target/wms-inventory-0.1.0-SNAPSHOT.jar").toString(),
                "--server.port="+port,"--wms.tcc.rm.enabled=true","--wms.tcc.rm.network-isolation-confirmed=true","--wms.tcc.rm.cell-id="+cell,
                "--wms.tcc.cluster-id=rm-it","--wms.tcc.transaction-group=wms_rm_group","--wms.tcc.servers=127.0.0.1:"+tcPort,"--wms.tcc.xid-addresses="+advertised);
        builder.environment().putAll(Map.of("WMS_INVENTORY_JDBC_URL",db.getJdbcUrl(),"WMS_INVENTORY_DB_USER",db.getUsername(),"WMS_INVENTORY_DB_PASSWORD",db.getPassword(),
                "WMS_OIDC_ISSUER",issuer,"WMS_OIDC_JWK_SET_URI",issuer+"/jwks","WMS_OIDC_CLIENT_ID","wms-platform"));
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(cell+".log").toFile())).start();
    }
    private HttpResponse<String> post(int port,String warehouse,String xid,WarehouseTryRequest request,String token)throws Exception {
        return post(port,warehouse,xid,RuntimeMessage.JSON.writeValueAsString(request),token);
    }
    private HttpResponse<String> post(int port,String warehouse,String xid,String json,String token)throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/wms/v1/warehouses/"+warehouse+"/tcc/tries"))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).header("TX_XID",xid).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static WarehouseTryResult ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());return RuntimeMessage.JSON.readValue(response.body(),WarehouseTryResult.class);}
    private boolean healthy(int port){try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception e){return false;}}
    private static WarehouseTryRequest request(String wh,String attempt,int qty){return new WarehouseTryRequest(1,"ENT",wh,"OWNER","ALLOC-"+attempt,attempt,wh,1,List.of(new WarehouseTryRequest.Line("L1","SKU","LOC","NO_LOT",BigDecimal.valueOf(qty),"EA",0)));}
    private static void seed(MySQLContainer db,String wh){
        var factory=InventoryPersistence.sessions(source(db),new JdbcTransactionFactory(),new DatabaseBudget(4,0,500,250,1,500,1500));
        try(var session=factory.openSession(false)){
            var master=new MasterdataService(session,Clock.systemUTC());master.createWarehouse(wh,"ENT",wh,"测试仓","UTC");
            master.createLocation("LOC","GATE","ENT",wh,"LOC","A","STORAGE",new BigDecimal("1000"),"EA");
            master.createSku(SkuPolicy.create("SKU","ENT","SKU","测试SKU","EA",0,false,false,false,1,"ACTIVE"),"UNIT");
            new InventoryApplicationService(session,Clock.systemUTC()).receive("ENT",wh,"RECEIVE","DOC","ACTOR",StockBucketKey.of("ENT",wh,"OWNER","LOC","SKU","NO_LOT","GOOD"),Quantity.parse("100",0));
            session.getMapper(WarehouseRouteMapper.class).insertIgnore("ROUTE","ENT",wh,wh,null,1,"ACTIVE",null,java.sql.Timestamp.from(Instant.now()));session.commit();
        }
    }
    private static String token(String issuer,RSAKey rsa,String subject,List<String> warehouses,String scope)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject(subject).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("enterprise_id","ENT").claim("warehouses",warehouses).claim("scope",List.of(scope)).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rm-it").build(),claims);jwt.sign(new RSASSASigner(rsa));return jwt.serialize();
    }
    private static String state(JdbcTemplate db,String attempt){return db.queryForObject("SELECT state FROM inventory_tcc_intent WHERE attempt_id=?",String.class,attempt);}
    private static int count(JdbcTemplate db,String sql){return db.queryForObject(sql,Integer.class);}
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db){var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setURL(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC"));source.setUser(db.getUsername());source.setPassword(db.getPassword());return source;}
    private static int port()throws Exception{try(var socket=new ServerSocket(0)){return socket.getLocalPort();}}
    private static void stop(Process p)throws Exception{if(p==null)return;p.destroy();if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(5,TimeUnit.SECONDS);}}
    private static void await(BooleanSupplier check,int seconds,String message,Process... processes)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();do{for(var p:processes)assertTrue(p.isAlive(),message);if(check.getAsBoolean())return;Thread.sleep(150);}while(System.nanoTime()<end);fail(message);}
}
