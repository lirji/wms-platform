package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.messaging.AllocationAuthorizationMessage;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL证明原货主/行不可替换，快照最后写失败不能留下ALLOCATED或半份Outbox。 */
class AllocationAuthorizationSnapshotIT {
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate db;
    private static final Clock CLOCK=Clock.systemUTC();
    private static final String DIGEST="a".repeat(64);
    @BeforeAll static void setup() {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_fulfillment").withPassword(UUID.randomUUID().toString());mysql.start();
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
        source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load().migrate();
        var config=new Configuration(new Environment("snapshot",new JdbcTransactionFactory(),source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);config.addMapper(FulfillmentMapper.class);config.addMapper(AllocationRecoveryMapper.class);
        config.addMapper(com.lrj.wms.runtime.messaging.persistence.MessageRecoveryMapper.class);
        config.addMapper(FulfillmentCancelMapper.class);
        sessions=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup() {if(mysql!=null)mysql.stop();}

    @Test void originalDigestCannotHideChangedOwnerStrategyOrLines() {
        try(var session=sessions.openSession(false)) {
            var service=new FulfillmentService(session,CLOCK);
            var first=service.createOrder("ENT-OWNER","OMS","SO",DIGEST,lines("2"),1,"OWNER");
            assertEquals(first.get("id"),service.createOrder("ENT-OWNER","OMS","SO",DIGEST,lines("2.000"),1,"OWNER").get("id"));
            assertEquals("OWNER",first.get("ownerId"));
            assertEquals("ORDER_CONFLICT",assertThrows(FulfillmentException.class,()->service.createOrder("ENT-OWNER","OMS","SO",DIGEST,lines("2"),1,"OTHER")).code());
            assertEquals("ORDER_CONFLICT",assertThrows(FulfillmentException.class,()->service.createOrder("ENT-OWNER","OMS","SO",DIGEST,lines("3"),1,"OWNER")).code());
            assertEquals("ORDER_CONFLICT",assertThrows(FulfillmentException.class,()->service.createOrder("ENT-OWNER","OMS","SO",DIGEST,lines("2"),2,"OWNER")).code());
            service.createOrder("ENT-OWNER","OMS","OLD",DIGEST,lines("2"),1);
            assertEquals("ORDER_CONFLICT",assertThrows(FulfillmentException.class,()->service.createOrder("ENT-OWNER","OMS","OLD",DIGEST,lines("2"),1,"OWNER")).code());
            session.commit();
        }
        assertNull(db.queryForObject("SELECT owner_id FROM fulfillment_order WHERE source_order_no='OLD'",String.class));
    }

    @Test void finalSnapshotFailureRollsBackBarrierAndReplayKeepsOriginalEvidence() {
        String attempt;
        try(var session=sessions.openSession(false)) {
            var service=new FulfillmentService(session,CLOCK);
            String order=String.valueOf(service.createOrder("ENT-BARRIER","OMS","SO",DIGEST,lines("2"),1,"OWNER").get("id"));
            attempt=String.valueOf(service.createAttempt("ENT-BARRIER",order,Instant.now().plusSeconds(300),List.of("WH-A","WH-B"),
                    List.of(plan("WH-A"),plan("WH-B"))).get("id"));
            service.claimLaunch("ENT-BARRIER",attempt,"fixture");
            service.bindXid("ENT-BARRIER",attempt,"fixture","fixture-xid",new TcEvidenceScope("fixture-cluster","wms-fulfillment","fixture-group"));
            service.observeTc("ENT-BARRIER",attempt,"Committed",proof());
            for(String warehouse:List.of("WH-A","WH-B")) {
                service.bindParticipant("ENT-BARRIER",attempt,warehouse,"fixture-xid",warehouse.equals("WH-A")?1:2,"ReservationTccAction","RES-"+warehouse,1,"TRIED");
                service.observeParticipant("ENT-BARRIER",attempt,warehouse,"CONFIRMED",1L);
                assertEquals(1,session.getMapper(FulfillmentMapper.class).bindConfirmedAllocation("ENT-BARRIER",attempt,warehouse,"ALLOC",Timestamp.from(Instant.now())));
            }
            session.commit();
        }
        db.execute("ALTER TABLE fulfillment_outbox ADD CONSTRAINT ck_test_snapshot CHECK(delivery_payload IS NULL OR warehouse_id<>'WH-B')");
        try(var session=sessions.openSession(false)) {
            var failure=assertThrows(RuntimeException.class,()->new FulfillmentService(session,CLOCK).markAllocated("ENT-BARRIER",attempt));
            assertTrue(failure.getMessage().contains("ck_test_snapshot"), "必须命中预期数据库CHECK，不能以其他异常冒充回滚验证");session.rollback();
        }
        assertEquals("TCC_TRYING",db.queryForObject("SELECT state FROM allocation_attempt WHERE id=?",String.class,attempt));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox",Integer.class));
        db.execute("ALTER TABLE fulfillment_outbox DROP CHECK ck_test_snapshot");
        try(var session=sessions.openSession(false)) {new FulfillmentService(session,CLOCK).markAllocated("ENT-BARRIER",attempt);session.commit();}
        var first=db.queryForList("SELECT event_id,delivery_payload FROM fulfillment_outbox ORDER BY event_id");
        assertEquals(5,first.size());
        for(var row:first) if(row.get("delivery_payload")!=null) {
            var body=AllocationAuthorizationMessage.parse(RuntimeMessage.JSON.readTree(row.get("delivery_payload").toString()));
            assertEquals("OWNER",body.ownerId());assertEquals(2,body.participants().size());assertEquals(attempt,body.attemptId());
        }
        try(var session=sessions.openSession(false)) {new FulfillmentService(session,CLOCK).markAllocated("ENT-BARRIER",attempt);session.commit();}
        assertEquals(first,db.queryForList("SELECT event_id,delivery_payload FROM fulfillment_outbox ORDER BY event_id"));
        try(var session=sessions.openSession(false)) {
            var service=new FulfillmentService(session,CLOCK);
            String order=String.valueOf(session.getMapper(FulfillmentMapper.class).lockAttempt("ENT-BARRIER",attempt).get("fulfillment_id"));
            service.requestCancel("ENT-BARRIER",order,"CANCEL-AFTER-AUTH","取消只是请求，已签发授权另行补偿",null,"operator");
            service.markAllocated("ENT-BARRIER",attempt);session.commit();
        }
        assertEquals(first,db.queryForList("SELECT event_id,delivery_payload FROM fulfillment_outbox ORDER BY event_id"));
        String event=db.queryForObject("SELECT event_id FROM fulfillment_outbox WHERE warehouse_id='WH-A' AND event_type='ExecutionAuthorizationRequested'",String.class);
        String payload=db.queryForObject("SELECT CAST(delivery_payload AS CHAR) FROM fulfillment_outbox WHERE event_id=?",String.class,event);
        db.update("UPDATE fulfillment_outbox SET status='ISOLATED',claim_epoch=9,error_code='RETRY_EXHAUSTED' WHERE event_id=?",event);
        var recovery=new com.lrj.wms.runtime.messaging.MessageRecoveryService(sessions,
                com.lrj.wms.runtime.messaging.MessageQueueMetrics.Queue.FULFILLMENT_OUTBOX,
                new com.lrj.wms.runtime.messaging.RuntimeInbox(sessions,Map.of(),CLOCK),CLOCK);
        recovery.retry("ENT-BARRIER","WH-A","OUTBOX",event,"RETRY-AUTH",9,"检查原授权后重排","operator");
        assertEquals(RuntimeMessage.hash(payload),db.queryForObject("SELECT payload_hash FROM message_recovery_audit WHERE command_id='RETRY-AUTH'",String.class));
        assertEquals(9L,db.queryForObject("SELECT claim_epoch FROM fulfillment_outbox WHERE event_id=?",Long.class,event));
        try(var session=sessions.openSession(false)) {
            session.getMapper(FulfillmentMapper.class).insertOutboxIgnore("LEGACY","ENT-BARRIER","LEGACY-ATT","WH-A", "ExecutionAuthorizationRequested","b".repeat(64),"{}",Timestamp.from(Instant.now()));session.commit();
        }
        db.update("UPDATE fulfillment_outbox SET status='ISOLATED',claim_epoch=9,error_code='LEGACY_AUTHORIZATION_CONTEXT_MISSING' WHERE event_id='LEGACY'");
        assertEquals("MESSAGE_NOT_REPLAYABLE",assertThrows(com.lrj.wms.runtime.messaging.MessageRecoveryException.class,
                ()->recovery.retry("ENT-BARRIER","WH-A","OUTBOX","LEGACY","RETRY-LEGACY",9,"旧事件缺少事实","operator")).code());
    }
    @Test void cancellationBeforeFirstBarrierDoesNotIssueAuthorization() {
        String attempt;
        try(var session=sessions.openSession(false)) {
            var service=new FulfillmentService(session,CLOCK);
            String order=String.valueOf(service.createOrder("ENT-CANCEL","OMS","SO-CANCEL",DIGEST,lines("1"),1,"OWNER").get("id"));
            attempt=String.valueOf(service.createAttempt("ENT-CANCEL",order,Instant.now().plusSeconds(120),List.of("WH-A"),List.of(plan("WH-A"))).get("id"));
            service.claimLaunch("ENT-CANCEL",attempt,"fixture");
            service.bindXid("ENT-CANCEL",attempt,"fixture","cancel-xid",new TcEvidenceScope("fixture-cluster","wms-fulfillment","fixture-group"));
            service.observeTc("ENT-CANCEL",attempt,"Committed",proof().replace("fixture-xid","cancel-xid"));
            service.bindParticipant("ENT-CANCEL",attempt,"WH-A","cancel-xid",10,"ReservationTccAction","RES-CANCEL",1,"TRIED");
            service.observeParticipant("ENT-CANCEL",attempt,"WH-A","CONFIRMED",1L);
            assertEquals(1,session.getMapper(FulfillmentMapper.class).bindConfirmedAllocation("ENT-CANCEL",attempt,"WH-A","ALLOC-CANCEL",Timestamp.from(Instant.now())));
            service.requestCancel("ENT-CANCEL",order,"CANCEL-BEFORE-AUTH","尚未签发授权",null,"operator");
            assertEquals("CANCEL_REQUIRES_COMPENSATION",assertThrows(FulfillmentException.class,()->service.markAllocated("ENT-CANCEL",attempt)).code());
            session.commit();
        }
        assertEquals("TCC_TRYING",db.queryForObject("SELECT state FROM allocation_attempt WHERE id=?",String.class,attempt));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=?",Integer.class,attempt));
    }
    private static List<Map<String,Object>> lines(String qty) {return List.of(Map.of("sourceLineId","L1","skuId","SKU","requestedQty",new BigDecimal(qty),"baseUnit","EA"));}
    private static Map<String,Object> plan(String wh) {return Map.of("warehouseId",wh,"orderLineId","L1","skuId","SKU","qty",BigDecimal.ONE,"baseUnit","EA");}
    private static String proof() {return RuntimeMessage.JSON.writeValueAsString(Map.of("xid","fixture-xid","status",9,"clusterId","fixture-cluster","applicationId","wms-fulfillment","transactionGroup","fixture-group"));}
}
