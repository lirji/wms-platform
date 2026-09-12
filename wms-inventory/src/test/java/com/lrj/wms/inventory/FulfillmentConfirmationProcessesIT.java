package com.lrj.wms.inventory;

import com.lrj.wms.fulfillment.*;
import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.ReservationLineInput;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.runtime.db.DatabaseBudget;
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

/** 实际库存/履约Jar和Kafka确认链；TC证据与Try由明确夹具提供，不冒充真实TM/RM协调。 */
class FulfillmentConfirmationProcessesIT {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String XID = "fixture-confirmation-xid";
    private static final String DIGEST = "c".repeat(64);

    @Test
    void durableWarehouseConfirmationWaitsForBindingAndRollsBackWithInboxBeforeRestart() throws Exception {
        Path root = Path.of("..").toRealPath(), logs = Path.of("target/fulfillment-confirmation-processes").toAbsolutePath();
        Files.createDirectories(logs);
        // 本例不调用受保护HTTP命令；只配置隔离OIDC来源，鉴权黑盒沿用既有HTTP门禁。
        var jwks = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        jwks.createContext("/jwks", exchange -> {
            byte[] body = "{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        }); jwks.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort();
        Process stockProcess=null, fulfillmentProcess=null;
        try (var stock = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory").withPassword(UUID.randomUUID().toString());
             var fulfillment = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment").withPassword(UUID.randomUUID().toString());
             var kafka = new KafkaContainer("apache/kafka:3.8.0")) {
            stock.start(); fulfillment.start(); kafka.start();
            var settings = new KafkaSettings(true,kafka.getBootstrapServers(),"wms.confirm", "PLAINTEXT","","");
            try (var admin=AdminClient.create(settings.connection())) {
                admin.createTopics(List.of(new NewTopic("wms.confirm.inventory.events",1,(short)1),
                        new NewTopic("wms.confirm.inbound.commands",1,(short)1),
                        new NewTopic("wms.confirm.fulfillment.results",1,(short)1))).all().get(20,TimeUnit.SECONDS);
            }
            int stockPort=port(), fulfillmentPort=port();
            stockProcess=start(root,"inventory",stock,kafka,stockPort,issuer,logs);
            fulfillmentProcess=start(root,"fulfillment",fulfillment,kafka,fulfillmentPort,issuer,logs);
            Process sp=stockProcess, fp=fulfillmentProcess;
            await(()->health(stockPort,"liveness") && health(fulfillmentPort,"liveness"),75,"服务未启动，日志="+logs,sp,fp);
            var stockDb = new JdbcTemplate(source(stock)); var ffDb = new JdbcTemplate(source(fulfillment));
            assertEquals(2,ffDb.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('runtime_message_inbox','message_recovery_audit')",Integer.class));
            var stockSessions = new InventoryPersistence().sqlSessionFactory(source(stock),null,new DatabaseBudget(4,0,1000,500,5,1000,10000));
            var config = new Configuration(new Environment("ff-fixture",new JdbcTransactionFactory(),source(fulfillment)));
            com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
            config.addMapper(FulfillmentMapper.class); config.addMapper(AllocationRecoveryMapper.class);
            var ffSessions = new SqlSessionFactoryBuilder().build(config);
            try(var session=stockSessions.openSession(false)) {
                var master=new MasterdataService(session,CLOCK);
                master.createSku(SkuPolicy.create("SKU","ENT","SKU","确认商品","EA",0,false,false,false,1,"ACTIVE"),"UNIT");
                for(String warehouse:List.of("WH-A","WH-B")) {
                    master.createWarehouse(warehouse,"ENT",warehouse,warehouse,"UTC");
                    master.createLocation("LOC-"+warehouse,"GATE-"+warehouse,"ENT",warehouse,"LOC","A","STORAGE",new BigDecimal("100"),"EA");
                    new InventoryApplicationService(session,CLOCK).receive("ENT",warehouse,"RECEIVE-"+warehouse,"DOC","test-fixture",bucket(warehouse),Quantity.parse("5",0));
                }
                session.commit();
            }
            String attempt;
            try(var session=ffSessions.openSession(false)) {
                var service=new FulfillmentService(session,CLOCK);
                String order=String.valueOf(service.createOrder("ENT","OMS","CONFIRM-ORDER",DIGEST,
                        List.of(Map.of("sourceLineId","L1","skuId","SKU","requestedQty",new BigDecimal("2"),"baseUnit","EA")),1).get("id"));
                attempt=String.valueOf(service.createAttempt("ENT",order,Instant.now().plusSeconds(300),List.of("WH-A","WH-B"),
                        List.of(plan("WH-A"),plan("WH-B"))).get("id"));
                service.claimLaunch("ENT",attempt,"tm-fixture");
                service.bindXid("ENT",attempt,"tm-fixture",XID,new TcEvidenceScope("fixture-cluster","wms-fulfillment","fixture-group"));
                service.observeTc("ENT",attempt,"Committed","{\"xid\":\""+XID+"\",\"status\":9}");
                session.commit();
            }
            Map<String,String> reservations=new HashMap<>();
            for (String warehouse:List.of("WH-A","WH-B")) {
                long branch=warehouse.equals("WH-A")?11:12;
                try(var session=stockSessions.openSession(false)) {
                    new InventoryApplicationService(session,CLOCK).reserveTried("ENT",warehouse,"TRY-"+warehouse,"DOC","tm-fixture","ALLOC",attempt,XID,
                            branch,"ReservationTccAction",1,DIGEST,List.of(new ReservationLineInput(bucket(warehouse),Quantity.parse("1",0),"L1")));
                    reservations.put(warehouse,String.valueOf(session.getMapper(InventoryMapper.class).lockReservationByAttempt("ENT",warehouse,"ALLOC",attempt).get("id")));
                    session.commit();
                }
            }
            bind(ffSessions,attempt,"WH-A",11,reservations.get("WH-A"));
            ffDb.execute("ALTER TABLE runtime_message_inbox ADD CONSTRAINT ck_test_final_inbox CHECK(status<>'DONE' OR warehouse_id<>'WH-B')");
            for(String warehouse:List.of("WH-A","WH-B")) {
                try(var session=stockSessions.openSession(false)) {
                    new InventoryApplicationService(session,CLOCK).confirmTried("ENT",warehouse,"CONFIRM-"+warehouse,"DOC","tc-fixture","ALLOC",attempt,
                            XID,warehouse.equals("WH-A")?11:12,"ReservationTccAction");
                    session.commit();
                }
            }
            await(()->count(ffDb,"SELECT COUNT(*) FROM allocation_participant WHERE state='CONFIRMED'")==1
                    && count(ffDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE warehouse_id='WH-B' AND claim_epoch>=1")==1,
                    40,"第一仓确认或未绑定消息未进入持久等待",sp,fp);
            assertEquals(0,count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox"));
            assertEquals("PLANNED",ffDb.queryForObject("SELECT state FROM allocation_participant WHERE warehouse_id='WH-B'",String.class));
            bind(ffSessions,attempt,"WH-B",12,reservations.get("WH-B"));
            long previous=ffDb.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE warehouse_id='WH-B'",Long.class);
            await(()->ffDb.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE warehouse_id='WH-B'",Long.class)>previous
                    && "PENDING".equals(ffDb.queryForObject("SELECT status FROM runtime_message_inbox WHERE warehouse_id='WH-B'",String.class)),
                    35,"最后Inbox失败未回滚并重排",sp,fp);
            assertEquals("PLANNED",ffDb.queryForObject("SELECT state FROM allocation_participant WHERE warehouse_id='WH-B'",String.class));
            assertNull(ffDb.queryForObject("SELECT confirmed_allocation_id FROM allocation_participant WHERE warehouse_id='WH-B'",String.class));
            assertEquals("TCC_TRYING",ffDb.queryForObject("SELECT state FROM allocation_attempt",String.class));
            assertEquals(0,count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox"));
            stop(fulfillmentProcess); fulfillmentProcess=null;
            ffDb.execute("ALTER TABLE runtime_message_inbox DROP CHECK ck_test_final_inbox");
            fulfillmentProcess=start(root,"fulfillment",fulfillment,kafka,fulfillmentPort,issuer,logs);
            Process recovered=fulfillmentProcess;
            await(()->"ALLOCATED".equals(ffDb.queryForObject("SELECT state FROM allocation_attempt",String.class)),50,"重启未按原确认恢复屏障",sp,recovered);
            assertEquals(5,count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox"));
            assertEquals(2,count(ffDb,"SELECT COUNT(*) FROM allocation_participant WHERE confirmed_allocation_id='ALLOC' AND confirmed_version=1"));
            assertEquals(2,count(ffDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='DONE'"));
            var original=RuntimeMessage.JSON.readTree(stockDb.queryForObject("SELECT payload FROM outbox_event WHERE event_type='ReservationConfirmed' AND warehouse_id='WH-B'",String.class));
            try(var publisher=new KafkaMessagePublisher(settings,"confirmation-replay-probe")) {
                publish(publisher,"CONFIRM-REPLAY",reservations.get("WH-B"),original);
                var invalid=original.deepCopy(); ((tools.jackson.databind.node.ObjectNode)invalid).put("branchId",999L);
                publish(publisher,"CONFIRM-WRONG-BRANCH",reservations.get("WH-B"),invalid);
            }
            await(()->count(ffDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='DONE'")==3
                    && count(ffDb,"SELECT COUNT(*) FROM runtime_message_inbox WHERE error_code='RESERVATION_BRANCH_MISMATCH'")==1,
                    25,"重复确认或错分支未被正确处理",sp,recovered);
            assertEquals(5,count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox"));
            bind(ffSessions,attempt,"WH-B",12,reservations.get("WH-B"));
            try(var session=ffSessions.openSession()) {
                var participants=session.getMapper(FulfillmentMapper.class).listParticipants("ENT",attempt);
                assertTrue(participants.stream().allMatch(row->"CONFIRMED".equals(row.get("state"))
                        && "CONFIRMED".equals(row.get("observed_branch_state"))));
            }
            assertEquals(2,count(stockDb,"SELECT COUNT(*) FROM reservation WHERE state='CONFIRMED'"));
            assertEquals(2,count(stockDb,"SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'"));
            var future=original.deepCopy(); ((tools.jackson.databind.node.ObjectNode)future).put("confirmationSchemaVersion",2);
            try(var session=stockSessions.openSession(false)) {
                session.getMapper(com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper.class).insertPending("FUTURE-CONFIRM","ENT","WH-B",InventoryCodes.AGGREGATE_RESERVATION,
                        reservations.get("WH-B"),1,"ReservationConfirmed","FUTURE-CONFIRM",future.toString(),java.sql.Timestamp.from(Instant.now()));
                session.commit();
            }
            await(()->"ISOLATED".equals(stockDb.queryForObject("SELECT status FROM outbox_event WHERE event_id='FUTURE-CONFIRM'",String.class)),15,"未知版本不能静默降级并丢失",sp,recovered);
            assertEquals(5,count(ffDb,"SELECT COUNT(*) FROM fulfillment_outbox"));
            await(()->health(fulfillmentPort,"readiness"),15,"履约消息依赖未就绪",sp,recovered);
        } finally { stop(stockProcess); stop(fulfillmentProcess); jwks.stop(0); }
    }

    private static void bind(SqlSessionFactory sessions,String attempt,String warehouse,long branch,String reservation) {
        try(var session=sessions.openSession(false)) {
            new FulfillmentService(session,CLOCK).bindParticipant("ENT",attempt,warehouse,XID,branch,"ReservationTccAction",reservation,1,"TRIED"); session.commit();
        }
    }
    private static Map<String,Object> plan(String warehouse) { return Map.of("warehouseId",warehouse,"orderLineId","L1","skuId","SKU","qty",BigDecimal.ONE,"baseUnit","EA"); }
    private static StockBucketKey bucket(String warehouse) { return StockBucketKey.of("ENT",warehouse,"OWNER","LOC-"+warehouse,"SKU","NO_LOT","GOOD"); }
    private static void publish(KafkaMessagePublisher publisher,String id,String reservation,tools.jackson.databind.JsonNode body) {
        publisher.publish("wms.confirm.fulfillment.results",reservation,new RuntimeMessage(1,id,"wms-inventory","ENT","WH-B","ReservationConfirmed",
                reservation,1,Instant.now().toString(),id,body).encode());
    }
    private static int count(JdbcTemplate db,String sql) { return db.queryForObject(sql,Integer.class); }
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {
        var source=new com.mysql.cj.jdbc.MysqlDataSource(); source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC"));
        source.setUser(db.getUsername()); source.setPassword(db.getPassword()); return source;
    }
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs) throws Exception {
        Path jar=root.resolve("wms-"+service+"/target/wms-"+service+"-0.1.0-SNAPSHOT.jar"); assertTrue(Files.isRegularFile(jar));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-jar",jar.toString());
        var env=builder.environment(); env.put("WMS_HTTP_PORT",String.valueOf(port)); env.put("WMS_BIND_ADDRESS","127.0.0.1");
        env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_JDBC_URL",db.getJdbcUrl());
        env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_USER",db.getUsername()); env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_PASSWORD",db.getPassword());
        env.put("WMS_OIDC_ISSUER",issuer); env.put("WMS_OIDC_JWK_SET_URI",issuer+"/jwks"); env.put("WMS_OIDC_CLIENT_ID","wms-platform");
        env.put("WMS_MESSAGING_ENABLED","true"); env.put("WMS_MESSAGING_RECOVERYENABLED","true");
        env.put("WMS_MESSAGING_BOOTSTRAPSERVERS",kafka.getBootstrapServers()); env.put("WMS_MESSAGING_TOPICPREFIX","wms.confirm");
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
