package com.lrj.wms.inventory;

import com.lrj.wms.contract.tcc.*;
import com.lrj.wms.runtime.messaging.*;
import com.github.dockerjava.api.model.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static com.lrj.wms.inventory.RuntimeRmProcessesIT.*;

/** 实际履约TM、双库存RM、出库Jar及Kafka闭环；不调用领域方法伪造TC/仓确认或授权。 */
class AllocationExecutionProcessesIT {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Test void fulfillmentRestartAfterLostTryReceiptUsesOriginalXidAndReachesOutboundAuthorization() throws Exception {
        Path root=Path.of("..").toRealPath(),logs=Path.of("target/allocation-execution-processes").toAbsolutePath();Files.createDirectories(logs);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
        var rsa=new RSAKey.Builder((RSAPublicKey)keys.getPublic()).privateKey((RSAPrivateKey)keys.getPrivate()).keyID("execution-it").build();
        var jwks=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        jwks.createContext("/jwks",exchange->{byte[] body=new JWKSet(rsa.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}});jwks.start();
        String issuer="http://127.0.0.1:"+jwks.getAddress().getPort();Process pa=null,pb=null,pf=null,po=null;
        Path tokens=Files.createTempDirectory("wms-execution-token-");
        try(var tcDb=mysql("seata");var a=mysql("inventory_a");var b=mysql("inventory_b");var ff=mysql("fulfillment");var out=mysql("outbound");
            var kafka=new KafkaContainer("apache/kafka:3.8.0")) {
            tcDb.start();a.start();b.start();ff.start();out.start();kafka.start();
            var adminSource=source(tcDb);adminSource.setUser("root");
            Flyway.configure().dataSource(adminSource).locations("filesystem:"+root.resolve("wms-test-support/src/test/resources/db/tc-probe")).load().migrate();
            var adminDb=new JdbcTemplate(adminSource);
            adminDb.execute("CREATE USER 'tc_audit'@'%' IDENTIFIED BY '"+tcDb.getPassword()+"'");
            adminDb.execute("GRANT SELECT ON seata.terminal_evidence TO 'tc_audit'@'%'");
            var settings=new KafkaSettings(true,kafka.getBootstrapServers(),"wms.execution","PLAINTEXT","","");
            try(var admin=AdminClient.create(settings.connection())) {
                admin.createTopics(List.of("cancellation.results","tcc.terminals","transfer.commands","inventory.events","inbound.commands","inbound.results","outbound.commands","outbound.results","outbound.authorizations","fulfillment.results","fulfillment.events")
                        .stream().map(t->new NewTopic("wms.execution."+t,1,(short)1)).toList()).all().get(20,TimeUnit.SECONDS);
            }
            int tcPort=port();
            try(var tc=new GenericContainer<>("apache/seata-server:2.6.0")
                    .withEnv("SEATA_IP","127.0.0.1").withEnv("SEATA_PORT",String.valueOf(tcPort)).withEnv("SEATA_SERVER_RETRY_DEAD_THRESHOLD","1000")
                    .withEnv("STORE_MODE","db").withEnv("JAVA_OPTS","-Xms128m -Xmx256m")
                    .withEnv("SEATA_STORE_DB_DATASOURCE","druid").withEnv("SEATA_STORE_DB_DB_TYPE","mysql").withEnv("SEATA_STORE_DB_DRIVER_CLASS_NAME","com.mysql.cj.jdbc.Driver")
                    .withEnv("SEATA_STORE_DB_URL","jdbc:mysql://"+tcDb.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress()+":3306/seata?allowPublicKeyRetrieval=true&useSSL=false")
                    .withEnv("SEATA_STORE_DB_USER",tcDb.getUsername()).withEnv("SEATA_STORE_DB_PASSWORD",tcDb.getPassword())
                    .withExposedPorts(tcPort).withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",tcPort),new ExposedPort(tcPort))))
                    .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3))) {
                tc.start();int portA=port(),portB=port(),portF=port(),portO=port();
                String advertised=tc.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress()+":"+tcPort;
                List<String> rm=List.of("--wms.tcc.rm.enabled=true","--wms.tcc.rm.network-isolation-confirmed=true","--wms.tcc.cluster-id=execution-it",
                        "--wms.tcc.transaction-group=wms_execution_group","--wms.messaging.inventory-routing-json="+routing(),"--wms.tcc.servers=127.0.0.1:"+tcPort,"--wms.tcc.xid-addresses="+advertised);
                var argsA=new ArrayList<>(rm);argsA.add("--wms.tcc.rm.cell-id=A");var argsB=new ArrayList<>(rm);argsB.add("--wms.tcc.rm.cell-id=B");
                pa=start(root,"inventory",a,kafka,portA,issuer,logs,"A",argsA);pb=start(root,"inventory",b,kafka,portB,issuer,logs,"B",argsB);
                await(()->healthy(portA)&&healthy(portB),75,"库存启动失败，日志="+logs,pa,pb);seed(a,"A");seed(b,"B");
                Files.writeString(tokens.resolve(RuntimeMessage.hash("ENT")+".jwt"),token(issuer,rsa,"wms-fulfillment",List.of("A","B"),List.of("inventory.tcc.try")));
                var tm=new ArrayList<>(List.of("--wms.fulfillment.execution.enabled=true","--wms.fulfillment.execution.network-isolation-confirmed=true",
                        "--wms.fulfillment.execution.tc-servers=127.0.0.1:"+tcPort,"--wms.fulfillment.execution.cells-json="+RuntimeMessage.JSON.writeValueAsString(Map.of("A","http://127.0.0.1:"+portA,"B","http://127.0.0.1:"+portB)),
                        "--wms.fulfillment.execution.token-directory="+tokens,"--wms.fulfillment.execution.allow-http=true","--wms.fulfillment.execution.enterprises=ENT"));
                tm.addAll(List.of("--wms.tc.audit.enabled=true","--wms.tc.audit.cluster-id=execution-it","--wms.tc.audit.transaction-group=wms_execution_group"));
                pf=start(root,"fulfillment",ff,kafka,portF,issuer,logs,"fulfillment",tm,Map.of("WMS_TC_AUDIT_JDBC_URL",tcDb.getJdbcUrl(),"WMS_TC_AUDIT_USER","tc_audit","WMS_TC_AUDIT_PASSWORD",tcDb.getPassword()));
                po=start(root,"outbound",out,kafka,portO,issuer,logs,"outbound",List.of());
                await(()->healthy(portF)&&healthy(portO),75,"履约/出库启动失败，日志="+logs,pf,po);
                var ffSql=new JdbcTemplate(source(ff));var outSql=new JdbcTemplate(source(out));var sqlA=new JdbcTemplate(source(a));var sqlB=new JdbcTemplate(source(b));
                String operator=token(issuer,rsa,"operator",List.of("A","B"),List.of("fulfillment.create","fulfillment.execute","fulfillment.read","fulfillment.cancel"));
                String order=created(post(portF,"/api/wms/v1/fulfillments",operator,"ORDER",Map.of("sourceSystem","OMS","sourceOrderNo","SO-EXECUTION","ownerId","OWNER","lines",List.of(Map.of("sourceLineId","L1","skuId","SKU","requestedQty","2","baseUnit","EA")))),201).path("id").asString();
                String attempt=created(post(portF,"/api/wms/v1/fulfillments/"+order+"/attempts",operator,"ATTEMPT",Map.of("warehouses",List.of("A","B"),"lines",List.of(plan("A"),plan("B")))),201).path("id").asString();
                var body=Map.of("warehouses",List.of(request("A",attempt),request("B",attempt)));
                String path="/api/wms/v1/fulfillments/"+order+"/attempts/"+attempt+"/executions";
                assertEquals(403,post(portF,path,token(issuer,rsa,"operator",List.of("A"),List.of("fulfillment.execute")),"EXECUTE",body).statusCode());
                assertEquals(0,count(ffSql,"SELECT COUNT(*) FROM allocation_execution"));
                var invalidVersion=RuntimeMessage.JSON.readTree(RuntimeMessage.JSON.writeValueAsString(body));
                ((tools.jackson.databind.node.ObjectNode)invalidVersion.path("warehouses").get(0)).put("schemaVersion",4294967297L);
                assertEquals(400,post(portF,path,operator,"EXECUTE",invalidVersion).statusCode());
                var changedOwner=RuntimeMessage.JSON.readTree(RuntimeMessage.JSON.writeValueAsString(body));
                ((tools.jackson.databind.node.ObjectNode)changedOwner.path("warehouses").get(0)).put("ownerId","OTHER");
                var rejectedOwner=post(portF,path,operator,"EXECUTE",changedOwner);
                assertEquals(409,rejectedOwner.statusCode());assertTrue(rejectedOwner.body().contains("OWNER_MISMATCH"));
                assertEquals(0,count(ffSql,"SELECT COUNT(*) FROM allocation_execution"));
                assertEquals(0,count(adminDb,"SELECT COUNT(*) FROM global_table"));
                // 最后进度写失败发生在真实B Try之后，原B分支已预占；重启必须重读原回执，不新建全局事务。
                ffSql.execute("ALTER TABLE allocation_execution ADD CONSTRAINT ck_test_execution_receipt CHECK(next_warehouse<>2)");
                created(post(portF,path,operator,"EXECUTE",body),202);
                await(()->count(sqlB,"SELECT COUNT(*) FROM inventory_tcc_intent WHERE state='TRIED'")==1
                        &&count(ffSql,"SELECT COUNT(*) FROM allocation_execution WHERE next_warehouse=1 AND error_code='EXECUTION_STEP_UNKNOWN'")==1,
                        30,"真实Try最终进度故障未命中",pa,pb,pf,po);
                String original=ffSql.queryForObject("SELECT xid FROM allocation_execution WHERE attempt_id=?",String.class,attempt);
                long branchB=sqlB.queryForObject("SELECT branch_id FROM inventory_tcc_intent WHERE attempt_id=?",Long.class,attempt);
                assertEquals(0,count(outSql,"SELECT COUNT(*) FROM outbound_execution_authorization"));
                var originalGlobal=adminDb.queryForMap("SELECT * FROM global_table WHERE xid=?",original);
                var originalBranches=adminDb.queryForList("SELECT * FROM branch_table WHERE xid=? ORDER BY branch_id",original);
                stop(pf);pf=null;ffSql.execute("ALTER TABLE allocation_execution DROP CHECK ck_test_execution_receipt");
                pf=start(root,"fulfillment",ff,kafka,portF,issuer,logs,"fulfillment",tm,Map.of("WMS_TC_AUDIT_JDBC_URL",tcDb.getJdbcUrl(),"WMS_TC_AUDIT_USER","tc_audit","WMS_TC_AUDIT_PASSWORD",tcDb.getPassword()));
                await(()->count(ffSql,"SELECT COUNT(*) FROM allocation_execution WHERE state='COMPLETED'")==1
                        &&count(outSql,"SELECT COUNT(*) FROM outbound_execution_authorization")==2,55,"原XID重启未到达出库授权，日志="+logs,pa,pb,pf,po);
                assertEquals(original,ffSql.queryForObject("SELECT xid FROM allocation_attempt WHERE id=?",String.class,attempt));
                assertEquals(branchB,sqlB.queryForObject("SELECT branch_id FROM inventory_tcc_intent WHERE attempt_id=?",Long.class,attempt));
                assertEquals(1,count(adminDb,"SELECT COUNT(*) FROM terminal_evidence"));assertEquals(9,adminDb.queryForObject("SELECT terminal_status FROM terminal_evidence WHERE xid=?",Integer.class,original));
                assertEquals(1,count(sqlA,"SELECT COUNT(*) FROM inventory_tcc_intent"));assertEquals(1,count(sqlB,"SELECT COUNT(*) FROM inventory_tcc_intent"));
                await(()->count(sqlA,"SELECT COUNT(*) FROM inventory_tcc_terminal")==1 && count(sqlB,"SELECT COUNT(*) FROM inventory_tcc_terminal")==1,
                        30,"TC终态未通过真实Kafka可靠通知两个RM",pa,pb,pf,po);
                assertEquals(2,count(ffSql,"SELECT COUNT(*) FROM fulfillment_outbox WHERE event_type='TcTerminalNoticeV1'"));
                assertEquals(2,count(ffSql,"SELECT COUNT(*) FROM allocation_participant WHERE state='CONFIRMED'"));
                assertEquals(2,count(outSql,"SELECT COUNT(*) FROM outbound_order WHERE owner_id='OWNER' AND status='ALLOCATED'"));
                assertEquals(1,sqlA.queryForObject("SELECT reserved_qty FROM stock_balance",BigDecimal.class).intValueExact());
                assertEquals(1,sqlB.queryForObject("SELECT reserved_qty FROM stock_balance",BigDecimal.class).intValueExact());
                var replay=created(post(portF,path,operator,"EXECUTE",body),202);assertEquals("COMPLETED",replay.path("state").asString());
                var visible=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+portF+"/api/wms/v1/fulfillments/"+order)).header("Authorization","Bearer "+operator).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,visible.statusCode());assertEquals("COMPLETED",RuntimeMessage.JSON.readTree(visible.body()).path("execution").path("state").asString());
                // 原全局已提交之后取消：B最终凭证写失败不能提前释放，重启后仍按原取消决定补偿。
                sqlB.execute("ALTER TABLE stock_posting ADD CONSTRAINT ck_test_cancel_receipt CHECK(action<>'CANCEL')");
                String cancelPath="/api/wms/v1/fulfillments/"+order+"/cancellations";
                created(post(portF,cancelPath,operator,"CANCEL-COMMITTED",Map.of("clientOperationId","CANCEL-COMMITTED","reason","客户取消")),202);
                try { await(()->count(sqlA,"SELECT COUNT(*) FROM stock_posting WHERE action='CANCEL'")==1
                        &&count(sqlB,"SELECT COUNT(*) FROM runtime_message_inbox WHERE claim_epoch>0 AND status='PENDING'")>0,
                        40,"取消未形成原CANCEL或失败恢复事实",pa,pb,pf,po); }
                catch(AssertionError failure) {
                    throw new AssertionError("取消诊断：fulfillment="+ffSql.queryForList("SELECT event_type,status,error_code FROM fulfillment_outbox")
                        +" outbound="+outSql.queryForList("SELECT state,error_code FROM outbound_cancellation")
                        +" source="+outSql.queryForList("SELECT event_type,status,error_code FROM source_outbox")
                        +" inventory="+sqlA.queryForList("SELECT status,error_code FROM runtime_message_inbox")
                        +" outboundInbox="+outSql.queryForList("SELECT status,error_code FROM runtime_message_inbox"),failure);
                }
                assertEquals(1,sqlB.queryForObject("SELECT reserved_qty FROM stock_balance WHERE quality_code='GOOD'",BigDecimal.class).intValueExact());
                assertEquals("COMPENSATING",ffSql.queryForObject("SELECT state FROM fulfillment_cancellation",String.class));
                stop(pb);pb=null;sqlB.execute("ALTER TABLE stock_posting DROP CHECK ck_test_cancel_receipt");
                pb=start(root,"inventory",b,kafka,portB,issuer,logs,"B",argsB);
                await(()->count(ffSql,"SELECT COUNT(*) FROM fulfillment_cancellation WHERE state='COMPLETED'")==1,
                        60,"重启后原取消未完成逐仓回执",pa,pb,pf,po);
                created(post(portF,cancelPath,operator,"CANCEL-COMMITTED",Map.of("clientOperationId","CANCEL-COMMITTED","reason","客户取消")),202);
                assertEquals(1,count(sqlA,"SELECT COUNT(*) FROM stock_posting WHERE action='CANCEL'"));
                assertEquals(1,count(sqlB,"SELECT COUNT(*) FROM stock_posting WHERE action='CANCEL'"));
                assertEquals("Committed",ffSql.queryForObject("SELECT tc_observed_status FROM allocation_attempt WHERE id=?",String.class,attempt));
                assertEquals(2,count(ffSql,"SELECT COUNT(*) FROM fulfillment_cancellation_result WHERE state='COMPLETED'"));
                assertEquals(0,sqlA.queryForObject("SELECT reserved_qty FROM stock_balance WHERE quality_code='GOOD'",BigDecimal.class).intValueExact());
                assertEquals(0,sqlB.queryForObject("SELECT reserved_qty FROM stock_balance WHERE quality_code='GOOD'",BigDecimal.class).intValueExact());
                // 真实broker路由探针；命令正文为明确夹具，证明两个cell不再竞争同一库存消费组。
                try(var publisher=new KafkaMessagePublisher(settings,"cell-routing-probe")) {
                    publisher.publish("wms.execution.inbound.commands","A",receipt("A","ROUTE-A").encode());
                    publisher.publish("wms.execution.inbound.commands","B",receipt("B","ROUTE-B").encode());
                    await(()->count(sqlA,"SELECT COUNT(*) FROM stock_command WHERE command_id='ROUTE-A' AND state='APPLIED'")==1
                            &&count(sqlB,"SELECT COUNT(*) FROM stock_command WHERE command_id='ROUTE-B' AND state='APPLIED'")==1,
                            25,"两cell普通收货命令未各自落账",pa,pb,pf,po);
                    assertEquals(0,count(sqlA,"SELECT COUNT(*) FROM runtime_message_inbox WHERE enterprise_id='ENT' AND warehouse_id='B'"));
                    assertEquals(0,count(sqlB,"SELECT COUNT(*) FROM runtime_message_inbox WHERE enterprise_id='ENT' AND warehouse_id='A'"));
                    assertEquals(1,sqlA.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE quality_code='HOLD'",BigDecimal.class).intValueExact());
                    assertEquals(1,sqlB.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE quality_code='HOLD'",BigDecimal.class).intValueExact());
                    publisher.publish("wms.execution.inbound.commands","A",receipt("A","ROUTE-A").encode());
                    publisher.publish("wms.execution.inbound.commands","C",receipt("C","ROUTE-UNKNOWN").encode());
                    sqlA.update("UPDATE warehouse_route SET route_epoch=2 WHERE enterprise_id='ENT' AND warehouse_id='A'");
                    publisher.publish("wms.execution.inbound.commands","A",receipt("A","ROUTE-STALE").encode());
                    await(()->count(sqlA,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='ISOLATED' AND error_code='CELL_ROUTE_CHANGED' AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.eventId'))='ROUTE-STALE'")==1
                            &&count(sqlA,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='ISOLATED' AND error_code='CELL_ROUTE_UNREGISTERED'")==1
                            &&count(sqlB,"SELECT COUNT(*) FROM runtime_message_inbox WHERE status='ISOLATED' AND error_code='CELL_ROUTE_UNREGISTERED'")==1,
                            25,"未知仓或陈旧代际未保留隔离证据",pa,pb,pf,po);
                    assertEquals(0,count(sqlA,"SELECT COUNT(*) FROM stock_command WHERE command_id='ROUTE-STALE'"));
                    assertEquals(1,sqlA.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE quality_code='HOLD'",BigDecimal.class).intValueExact());
                }
                // 原B已收到真实TC终态及Kafka证明，停旧进程后迁移到新物理cell并重启原生RM。
                stop(pb);pb=null;
                try(var migrated=mysql("inventory_c")) {
                    migrated.start();var target=source(migrated);
                    new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", "").initialize(target,()->Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate());
                    var sourceSessions=InventoryPersistence.sessions(source(b),new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),new com.lrj.wms.runtime.db.DatabaseBudget(4,0,500,250,1,500,1500));
                    var targetSql=new JdbcTemplate(target);
                    try(var session=sourceSessions.openSession(false)) {
                        var migration=new com.lrj.wms.inventory.migrate.WarehouseMigrationService(session,sqlB,targetSql,Clock.systemUTC());
                        migration.prepare("ENT","B","B","C");session.commit();
                    }
                    try(var session=sourceSessions.openSession(false)) {
                        var migration=new com.lrj.wms.inventory.migrate.WarehouseMigrationService(session,sqlB,targetSql,Clock.systemUTC());
                        migration.copyFull("ENT","B");migration.quiesce("ENT","B");session.commit();
                    }
                    try(var session=sourceSessions.openSession(false)) {
                        new com.lrj.wms.inventory.migrate.WarehouseMigrationService(session,sqlB,targetSql,Clock.systemUTC()).switchEpoch("ENT","B");session.commit();
                    }
                    assertEquals(sqlB.queryForList("SELECT * FROM tcc_fence_log"),targetSql.queryForList("SELECT * FROM tcc_fence_log"));
                    assertEquals(sqlB.queryForList("SELECT * FROM inventory_tcc_intent"),targetSql.queryForList("SELECT * FROM inventory_tcc_intent"));
                    var argsC=new ArrayList<>(rm.stream().filter(arg->!arg.startsWith("--wms.messaging.inventory-routing-json=")).toList());
                    argsC.add("--wms.tcc.rm.cell-id=C");argsC.add("--wms.messaging.inventory-routing-json="+RuntimeMessage.JSON.writeValueAsString(Map.of("schemaVersion",1,"routes",List.of(
                            Map.of("enterpriseId","ENT","warehouseId","A","cellId","A","routeEpoch",2),
                            Map.of("enterpriseId","ENT","warehouseId","B","cellId","C","routeEpoch",2)))));
                    Process pc=start(root,"inventory",migrated,kafka,portB,issuer,logs,"C",argsC);
                    try {
                        await(()->healthy(portB),60,"迁移后原生RM未恢复资源并就绪",pc);
                        assertEquals(1,count(targetSql,"SELECT COUNT(*) FROM inventory_tcc_terminal"));
                        assertEquals(0,targetSql.queryForObject("SELECT reserved_qty FROM stock_balance WHERE quality_code='GOOD'",BigDecimal.class).intValueExact());
                        // 再次启动同一目标，原分支与Fence仍保留；不能重新Try产生新预占。
                        stop(pc);pc=start(root,"inventory",migrated,kafka,portB,issuer,logs,"C",argsC);
                        await(()->healthy(portB),60,"迁移目标重启未恢复历史资源",pc);
                        assertEquals(branchB,targetSql.queryForObject("SELECT branch_id FROM inventory_tcc_intent",Long.class));
                        assertEquals(1,count(targetSql,"SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'"));
                        // 仅在本测试TC库重放保存的原会话：模拟延迟/重复二阶段，不补造新的业务决定。
                        // 原全局9证据和原分支均来自此前真实执行；TC重新寻址原应用，B旧进程已停止。
                        tc.stop();assertEquals(0,count(adminDb,"SELECT COUNT(*) FROM global_table"));
                        originalGlobal.put("status",org.apache.seata.core.model.GlobalStatus.CommitRetrying.getCode());
                        insertOriginal(adminDb,"global_table",originalGlobal);
                        for(var branch:originalBranches) insertOriginal(adminDb,"branch_table",branch);
                        tc.start();
                        await(()->count(adminDb,"SELECT COUNT(*) FROM global_table")==0 && count(adminDb,"SELECT COUNT(*) FROM branch_table")==0,
                                90,"真实TC未按原应用/资源恢复迁移后的重复回调",pa,pc);
                        assertEquals(1,count(adminDb,"SELECT COUNT(*) FROM terminal_evidence"));
                        assertEquals(1,count(targetSql,"SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'"));
                        assertEquals(sqlB.queryForList("SELECT * FROM tcc_fence_log"),targetSql.queryForList("SELECT * FROM tcc_fence_log"));

                    } finally {stop(pc);}
                }
            }
        } finally {stop(pf);stop(pa);stop(pb);stop(po);jwks.stop(0);Files.deleteIfExists(tokens.resolve(RuntimeMessage.hash("ENT")+".jwt"));Files.deleteIfExists(tokens);}
    }
    /** 表与列仅来自本测试保存的TC官方表行，值参数绑定，不接收业务输入。 */
    private static void insertOriginal(JdbcTemplate jdbc,String table,Map<String,Object> row) {
        if(!Set.of("global_table","branch_table").contains(table) || row.keySet().stream().anyMatch(key->!key.matches("[a-z_]+"))) throw new IllegalArgumentException();
        jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    private static RuntimeMessage receipt(String wh,String command) {
        var body=new LinkedHashMap<String,Object>();body.put("commandId",command);body.put("action","RECEIVE");body.put("qty","1");
        body.put("factParentId","RECEIPT-"+command);body.put("factPartId","PART");body.put("factLineId","L1");body.put("actorId","operator");body.put("sourceExecutionId","EXEC-"+command);
        body.put("postingContext",new com.lrj.wms.contract.messaging.StockPostingContext("DOC-"+command,"OWNER","SKU","EA","LOC",null,"NO_LOT","HOLD",null,null));
        return new RuntimeMessage(1,command,"wms-inbound","ENT",wh,"StockCommandRequested",command,1,"2026-09-13T00:00:00Z",command,RuntimeMessage.JSON.readTree(RuntimeMessage.JSON.writeValueAsString(body)));
    }
    private static String routing(){return RuntimeMessage.JSON.writeValueAsString(Map.of("schemaVersion",1,"routes",List.of(
            Map.of("enterpriseId","ENT","warehouseId","A","cellId","A","routeEpoch",1),
            Map.of("enterpriseId","ENT","warehouseId","B","cellId","B","routeEpoch",1))));}
    private static MySQLContainer mysql(String db){return new MySQLContainer("mysql:8.4.11").withDatabaseName(db).withUsername("wms").withPassword(UUID.randomUUID().toString());}
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs,String name,List<String> args)throws Exception{return start(root,service,db,kafka,port,issuer,logs,name,args,Map.of());}
    private Process start(Path root,String service,MySQLContainer db,KafkaContainer kafka,int port,String issuer,Path logs,String name,List<String> args,Map<String,String> extra)throws Exception{
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx256m","-jar",root.resolve("wms-"+service+"/target/wms-"+service+"-0.1.0-SNAPSHOT.jar").toString()));command.addAll(args);
        var builder=new ProcessBuilder(command);var env=builder.environment();env.putAll(extra);
        env.put("WMS_HTTP_PORT",String.valueOf(port));env.put("WMS_BIND_ADDRESS","127.0.0.1");env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_JDBC_URL",db.getJdbcUrl());
        env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_USER",db.getUsername());env.put("WMS_"+service.toUpperCase(Locale.ROOT)+"_DB_PASSWORD",db.getPassword());
        env.put("WMS_OIDC_ISSUER",issuer);env.put("WMS_OIDC_JWK_SET_URI",issuer+"/jwks");env.put("WMS_OIDC_CLIENT_ID","wms-platform");
        env.put("WMS_MESSAGING_ENABLED","true");env.put("WMS_MESSAGING_BOOTSTRAPSERVERS",kafka.getBootstrapServers());env.put("WMS_MESSAGING_TOPICPREFIX","wms.execution");
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve(name+".log").toFile())).start();
    }
    private HttpResponse<String> post(int port,String path,String token,String key,Object body)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Idempotency-Key",key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(RuntimeMessage.JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private boolean healthy(int port){try{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health/readiness")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception e){return false;}}
    private static tools.jackson.databind.JsonNode created(HttpResponse<String> response,int expected){assertEquals(expected,response.statusCode(),response.body());return RuntimeMessage.JSON.readTree(response.body());}
    private static int count(JdbcTemplate db,String sql){return db.queryForObject(sql,Integer.class);}
    private static Map<String,Object> plan(String wh){return Map.of("warehouseId",wh,"orderLineId","L1","skuId","SKU","qty","1","baseUnit","EA");}
    private static WarehouseTryRequest request(String wh,String attempt){return new WarehouseTryRequest(1,"ENT",wh,"OWNER",attempt,attempt,wh,1,List.of(new WarehouseTryRequest.Line("L1","SKU","LOC","NO_LOT",BigDecimal.ONE,"EA",0)));}
    private static String token(String issuer,RSAKey rsa,String subject,List<String> warehouses,List<String> scopes)throws Exception{
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("execution-it").build(),new JWTClaimsSet.Builder().issuer(issuer).audience("wms-platform").subject(subject)
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).claim("enterprise_id","ENT").claim("warehouses",warehouses).claim("scope",scopes).build());jwt.sign(new RSASSASigner(rsa));return jwt.serialize();
    }
}
