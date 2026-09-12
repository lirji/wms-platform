package com.lrj.wms.inventory;

import com.lrj.wms.fulfillment.*;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
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

/** 两个实际Jar/两库/Kafka验证授权投递；真实TM/RM前置在本例明确由夹具提供。 */
class FulfillmentAuthorizationProcessesIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Clock CLOCK=Clock.systemUTC();
    private static final String XID="authorization-fixture-xid";

    @Test void outOfOrderAuthorizationAndFinalInboxFailureRecoverWithoutChangingOriginalScope() throws Exception {
        Path root=Path.of("..").toRealPath(),logs=Path.of("target/fulfillment-authorization-processes").toAbsolutePath();Files.createDirectories(logs);
        var jwks=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        jwks.createContext("/jwks",exchange->{byte[] body="{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}});jwks.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort();
        Process ffProcess=null,outProcess=null;
        try(var ff=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment").withPassword(UUID.randomUUID().toString());
            var out=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound").withPassword(UUID.randomUUID().toString());
            var kafka=new KafkaContainer("apache/kafka:3.8.0")) {
            ff.start();out.start();kafka.start();
            var settings=new KafkaSettings(true,kafka.getBootstrapServers(),"wms.authorization","PLAINTEXT","","");
            try(var admin=AdminClient.create(settings.connection())) {
                admin.createTopics(List.of("fulfillment.results","fulfillment.events","outbound.authorizations","outbound.commands","outbound.results")
                        .stream().map(s->new NewTopic("wms.authorization."+s,1,(short)1)).toList()).all().get(20,TimeUnit.SECONDS);
            }
            int ffPort=port(),outPort=port();
            ffProcess=start(root,"fulfillment",ff,kafka,ffPort,issuer,logs,false);
            outProcess=start(root,"outbound",out,kafka,outPort,issuer,logs,true);
            await(()->health(ffPort,"liveness")&&health(outPort,"liveness"),75,"启动失败，日志="+logs,ffProcess,outProcess);
            var ffDb=new JdbcTemplate(source(ff));var outDb=new JdbcTemplate(source(out));
            var config=new Configuration(new Environment("authorization-fixture",new JdbcTransactionFactory(),source(ff)));
            com.lrj.wms.runtime.db.DatabaseInstants.configure(config);config.addMapper(FulfillmentMapper.class);config.addMapper(AllocationRecoveryMapper.class);
            var sessions=new SqlSessionFactoryBuilder().build(config);
            String attempt;
            try(var session=sessions.openSession(false)) {
                var service=new FulfillmentService(session,CLOCK);
                String order=String.valueOf(service.createOrder("ENT","OMS","SO-AUTH","a".repeat(64),
                        List.of(Map.of("sourceLineId","L1","skuId","SKU","requestedQty",new BigDecimal("2"),"baseUnit","EA")),1,"OWNER").get("id"));
                attempt=String.valueOf(service.createAttempt("ENT",order,Instant.now().plusSeconds(300),List.of("WH-A","WH-B"),
                        List.of(plan("WH-A"),plan("WH-B"))).get("id"));
                service.claimLaunch("ENT",attempt,"tm-fixture");
                service.bindXid("ENT",attempt,"tm-fixture",XID,new TcEvidenceScope("fixture-cluster","wms-fulfillment","fixture-group"));
                service.observeTc("ENT",attempt,"Committed",RuntimeMessage.JSON.writeValueAsString(Map.of("xid",XID,"status",9,
                        "clusterId","fixture-cluster","applicationId","wms-fulfillment","transactionGroup","fixture-group")));
                for(String wh:List.of("WH-A","WH-B")) {
                    service.bindParticipant("ENT",attempt,wh,XID,wh.equals("WH-A")?11:12,"ReservationTccAction","RES-"+wh,1,"TRIED");
                    service.observeParticipant("ENT",attempt,wh,"CONFIRMED",1L);
                    assertEquals(1,session.getMapper(FulfillmentMapper.class).bindConfirmedAllocation("ENT",attempt,wh,"ALLOC",java.sql.Timestamp.from(Instant.now())));
                }
                service.markAllocated("ENT",attempt);session.commit();
            }
            // 控制到达顺序，不依赖线程调度偶然让授权先到。A只放授权，B只放建单。
            ffDb.update("UPDATE fulfillment_outbox SET next_attempt_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE)");
            ffDb.update("UPDATE fulfillment_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE (warehouse_id='WH-A' AND event_type='ExecutionAuthorizationRequested') OR (warehouse_id='WH-B' AND event_type='OutboundOrderRequested')");
            outDb.execute("ALTER TABLE runtime_message_inbox ADD CONSTRAINT ck_test_authority_done CHECK(status<>'DONE' OR warehouse_id<>'WH-A')");
            stop(ffProcess);ffProcess=start(root,"fulfillment",ff,kafka,ffPort,issuer,logs,true);
            await(()->count(outDb,"SELECT COUNT(*) FROM outbound_order WHERE warehouse_id='WH-B'")==1
                    &&count(outDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE warehouse_id='WH-A' AND claim_epoch>=1 AND status='PENDING'")==1,
                    45,"授权与建单未按原事件进入持久恢复",ffProcess,outProcess);
            assertEquals(0,count(outDb,"SELECT COUNT(*) FROM outbound_order WHERE warehouse_id='WH-A'"));
            assertEquals(0,count(outDb,"SELECT COUNT(*) FROM outbound_tcc_evidence WHERE warehouse_id='WH-A'"));
            assertEquals(0,count(outDb,"SELECT COUNT(*) FROM outbound_execution_authorization"));
            assertEquals("PENDING_AUTHORIZATION",outDb.queryForObject("SELECT status FROM outbound_order WHERE warehouse_id='WH-B'",String.class));
            stop(outProcess);outDb.execute("ALTER TABLE runtime_message_inbox DROP CHECK ck_test_authority_done");
            outProcess=start(root,"outbound",out,kafka,outPort,issuer,logs,true);
            await(()->count(outDb,"SELECT COUNT(*) FROM outbound_order WHERE warehouse_id='WH-A' AND status='ALLOCATED'")==1,
                    45,"重启未恢复原授权",ffProcess,outProcess);
            assertEquals(1,count(outDb,"SELECT COUNT(*) FROM outbound_execution_authorization"));
            assertEquals(0,count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox WHERE warehouse_id='WH-A' AND event_type='OutboundOrderRequested' AND status='PUBLISHED'"));
            // Broker已收到但最终PUBLISHED写失败；重复投递不能产生第二个授权或订单。
            ffDb.execute("ALTER TABLE fulfillment_outbox ADD CONSTRAINT ck_test_publish_receipt CHECK(status<>'PUBLISHED' OR warehouse_id<>'WH-B' OR event_type<>'ExecutionAuthorizationRequested')");
            ffDb.update("UPDATE fulfillment_outbox SET next_attempt_at=UTC_TIMESTAMP(6) WHERE status='PENDING'");
            await(()->count(outDb,"SELECT COUNT(*) FROM outbound_order WHERE status='ALLOCATED'")==2
                    &&count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox WHERE warehouse_id='WH-B' AND event_type='ExecutionAuthorizationRequested' AND status='PENDING' AND claim_epoch>=1")==1,
                    35,"发布最终写失败场景未到达",ffProcess,outProcess);
            ffDb.execute("ALTER TABLE fulfillment_outbox DROP CHECK ck_test_publish_receipt");
            await(()->count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox WHERE status='PUBLISHED'")==5
                    &&count(outDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='DONE'")==4,
                    30,"原事件没有恢复完成",ffProcess,outProcess);
            assertEquals(2,count(outDb,"SELECT COUNT(*) FROM outbound_order WHERE owner_id='OWNER' AND allocation_id='ALLOC'"));
            assertEquals(2,count(outDb,"SELECT COUNT(*) FROM outbound_tcc_evidence WHERE barrier_payload IS NOT NULL"));
            assertEquals(2,count(outDb,"SELECT COUNT(*) FROM outbound_execution_authorization"));
            assertEquals(2,count(outDb,"SELECT COUNT(*) FROM outbound_line WHERE order_line_id='L1' AND allocated_qty=1 AND base_unit='EA'"));
            var original=RuntimeMessage.JSON.readTree(ffDb.queryForObject("SELECT delivery_payload FROM fulfillment_outbox WHERE warehouse_id='WH-A' AND event_type='ExecutionAuthorizationRequested'",String.class));
            try(var publisher=new KafkaMessagePublisher(settings,"authorization-negative-probe")) {
                publish(publisher,"AUTH-REPLAY",attempt,original);
                var owner=original.deepCopy();((tools.jackson.databind.node.ObjectNode)owner).put("ownerId","OTHER");publish(publisher,"AUTH-OWNER",attempt,owner);
                var qty=original.deepCopy();((tools.jackson.databind.node.ObjectNode)qty.path("lines").get(0)).put("qty",2);publish(publisher,"AUTH-QTY",attempt,qty);
                var proof=original.deepCopy();((tools.jackson.databind.node.ObjectNode)proof.path("tcProof")).put("clusterId","other-cluster");publish(publisher,"AUTH-TC",attempt,proof);
                var future=original.deepCopy();((tools.jackson.databind.node.ObjectNode)future).put("authorizationSchemaVersion",4294967297L);publish(publisher,"AUTH-FUTURE",attempt,future);
            }
            await(()->count(outDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='DONE'")==5
                    &&count(outDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='ISOLATED'")==4,
                    30,"重复或错误授权未正确处理",ffProcess,outProcess);
            assertEquals(2,count(outDb,"SELECT COUNT(*) FROM outbound_order"));
            assertEquals(2,count(outDb,"SELECT COUNT(*) FROM outbound_execution_authorization"));
            assertEquals(0,count(outDb,"SELECT COUNT(*) FROM outbound_order WHERE owner_id='OTHER'"));
            await(()->health(ffPort,"readiness")&&health(outPort,"readiness"),20,"运行消息依赖未就绪",ffProcess,outProcess);
        } finally {stop(ffProcess);stop(outProcess);jwks.stop(0);}
    }
    private static Map<String,Object> plan(String wh) {return Map.of("warehouseId",wh,"orderLineId","L1","skuId","SKU","qty",BigDecimal.ONE,"baseUnit","EA");}
    private static void publish(KafkaMessagePublisher publisher,String id,String attempt,tools.jackson.databind.JsonNode body) {
        publisher.publish("wms.authorization.outbound.authorizations",attempt,new RuntimeMessage(1,id,"wms-fulfillment","ENT","WH-A",
                "ExecutionAuthorizationRequested",attempt,1,Instant.now().toString(),id,body).encode());
    }
    private static int count(JdbcTemplate db,String sql) { return db.queryForObject(sql,Integer.class); }
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {
        var source=new com.mysql.cj.jdbc.MysqlDataSource(); source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC"));
        source.setUser(db.getUsername()); source.setPassword(db.getPassword()); return source;
    }
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs,boolean messaging) throws Exception {
        Path jar=root.resolve("wms-"+service+"/target/wms-"+service+"-0.1.0-SNAPSHOT.jar"); assertTrue(Files.isRegularFile(jar));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",jar.toString());
        var env=builder.environment(); env.put("WMS_HTTP_PORT",String.valueOf(port)); env.put("WMS_BIND_ADDRESS","127.0.0.1");
        env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_JDBC_URL",db.getJdbcUrl());
        env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_USER",db.getUsername()); env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_PASSWORD",db.getPassword());
        env.put("WMS_OIDC_ISSUER",issuer); env.put("WMS_OIDC_JWK_SET_URI",issuer+"/jwks"); env.put("WMS_OIDC_CLIENT_ID","wms-platform");
        env.put("WMS_MESSAGING_ENABLED",String.valueOf(messaging)); env.put("WMS_MESSAGING_RECOVERYENABLED","true");
        env.put("WMS_MESSAGING_BOOTSTRAPSERVERS",kafka.getBootstrapServers()); env.put("WMS_MESSAGING_TOPICPREFIX","wms.authorization");
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(service+".log").toFile())).start();
    }
    private boolean health(int port,String group) {
        try { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/"+group)).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200; }
        catch(Exception unavailable) { return false; }
    }
    private static void await(BooleanSupplier done,int seconds,String error,Process... processes) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!done.getAsBoolean()) { for(var process:processes) assertTrue(process.isAlive(),error); assertTrue(System.nanoTime()<deadline,error); Thread.sleep(200); }
    }
    private static int port() throws Exception { try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static void stop(Process process) throws Exception { if(process==null)return; process.destroy(); if(!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS); } }
}
