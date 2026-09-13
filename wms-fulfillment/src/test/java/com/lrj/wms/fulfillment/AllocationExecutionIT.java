package com.lrj.wms.fulfillment;

import com.lrj.wms.contract.tcc.*;
import com.lrj.wms.runtime.messaging.RuntimeMessage;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证持久化编排及崩溃窗口；此类的TC/RM端口是故障夹具，不冒充完整网络链路。 */
class AllocationExecutionIT {
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate db;
    private static final Clock CLOCK=Clock.systemUTC();
    private static final TcEvidenceScope SCOPE=new TcEvidenceScope("CLUSTER","wms-fulfillment","GROUP");
    @BeforeAll static void setup() {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("execution").withPassword(UUID.randomUUID().toString());mysql.start();
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
        source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load().migrate();
        var config=new Configuration(new Environment("execution",new JdbcTransactionFactory(),source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(FulfillmentMapper.class);config.addMapper(AllocationExecutionMapper.class);config.addMapper(AllocationRecoveryMapper.class);config.addMapper(FulfillmentCancelMapper.class);
        sessions=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup(){if(mysql!=null)mysql.stop();}

    @Test void finalTryReceiptFailureReplaysOriginalBranchThenRestartsOriginalCommit() {
        var f=new Fixture("ENT-RECEIPT");f.submit();f.step();assertEquals("TRYING",f.state());
        db.execute("ALTER TABLE allocation_execution ADD CONSTRAINT ck_test_try_progress CHECK(enterprise_id<>'ENT-RECEIPT' OR next_warehouse<>1)");
        try {f.step();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM allocation_participant WHERE attempt_id=? AND branch_id IS NOT NULL",Integer.class,f.attempt));
            assertEquals("EXECUTION_STEP_UNKNOWN",f.value("error_code"));}
        finally {db.execute("ALTER TABLE allocation_execution DROP CHECK ck_test_try_progress");}
        f.step();assertEquals(2,f.tries.get());assertEquals(1,f.value("next_warehouse"));
        f.step();assertEquals("FINISH_REQUESTED",f.state());assertEquals("COMMIT",f.value("requested_action"));
        f.commitUnknown=true;f.step();assertEquals("WAITING_TERMINAL",f.state());assertEquals("TC_COMMIT_UNKNOWN",f.value("error_code"));
        f.commitUnknown=false;f.step();assertEquals(2,f.commits.get());assertEquals(1,f.begins.get());assertEquals(f.xid,f.value("xid"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE enterprise_id=? AND event_type<>'TcTerminalNoticeV1'",Integer.class,f.e));
        f.confirm();f.proof=proof(f.xid,"Committed",9);f.step();
        assertEquals("COMPLETED",f.state());assertEquals("ALLOCATED",db.queryForObject("SELECT state FROM allocation_attempt WHERE id=?",String.class,f.attempt));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE enterprise_id=? AND event_type<>'TcTerminalNoticeV1'",Integer.class,f.e));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE enterprise_id=? AND event_type='TcTerminalNoticeV1'",Integer.class,f.e));
        assertFalse(f.worker().executeOne(f.e));assertEquals(2,f.commits.get());
    }

    @Test void unknownBeginAndExpiredCallingLeaseNeverCreateAnotherGlobalTransaction() {
        var f=new Fixture("ENT-BEGIN");f.submit();f.beginUnknown=true;f.step();
        assertEquals("BEGIN_UNKNOWN",f.state());assertEquals(1,f.begins.get());assertEquals(0,f.tries.get());
        assertFalse(f.worker().executeOne(f.e));
        assertEquals("BEGIN_UNKNOWN",f.service().submit(f.e,f.order,f.attempt,"EXECUTE","operator",f.requests()).get("state"));
        assertEquals(1,f.begins.get());
        var crashed=new Fixture("ENT-CRASH");crashed.submit();
        try(var session=sessions.openSession(false)) {
            var execution=session.getMapper(AllocationExecutionMapper.class).lock(crashed.e,crashed.attempt);
            new FulfillmentService(session,CLOCK).claimLaunch(crashed.e,crashed.attempt,execution.get("launch_owner").toString());session.commit();
        }
        db.update("UPDATE allocation_execution SET state='BEGIN_CALLING',lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE attempt_id=?",crashed.attempt);
        crashed.step();assertEquals("BEGIN_UNKNOWN",crashed.state());assertEquals(0,crashed.begins.get());assertEquals(0,crashed.tries.get());
    }

    @Test void staleTryResultCannotAdvanceNewLeaseAndCommitIntentCannotFlipAfterCancellation() {
        var f=new Fixture("ENT-STALE");f.submit();f.step();f.stealTry=true;f.step();
        assertEquals(0,f.value("next_warehouse"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM allocation_participant WHERE attempt_id=? AND branch_id IS NOT NULL",Integer.class,f.attempt));
        f.stealTry=false;f.step();f.step();assertEquals("COMMIT",f.value("requested_action"));
        try(var session=sessions.openSession(false)) {new FulfillmentService(session,CLOCK).requestCancel(f.e,f.order,"CANCEL","OMS",null,"operator");session.commit();}
        f.step();assertEquals(1,f.commits.get());assertEquals(0,f.rollbacks.get());
        f.confirm();f.proof=proof(f.xid,"Committed",9);f.step();
        assertEquals("WAITING_TERMINAL",f.state());assertEquals("CANCEL_REQUIRES_COMPENSATION",f.value("error_code"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE enterprise_id=? AND event_type<>'TcTerminalNoticeV1'",Integer.class,f.e));
    }

    @Test void failedTryHasBoundedRetriesAndRollbackRequiresTcEvidence() {
        var f=new Fixture("ENT-ROLLBACK");f.submit();f.step();f.tryUnknown=true;
        for(int i=0;i<8;i++) f.step();assertEquals(8,f.tries.get());f.step();assertEquals("ROLLBACK",f.value("requested_action"));
        f.step();assertEquals(1,f.rollbacks.get());assertEquals("WAITING_TERMINAL",f.state());
        assertEquals("TCC_TRYING",db.queryForObject("SELECT state FROM allocation_attempt WHERE id=?",String.class,f.attempt));
        f.proof=proof(f.xid,"TimeoutRollbacked",13);f.step();assertEquals("ROLLED_BACK",f.state());
        assertEquals("CANCELLED",db.queryForObject("SELECT state FROM allocation_attempt WHERE id=?",String.class,f.attempt));
        assertEquals(1,f.begins.get());assertEquals(0,f.commits.get());
    }

    @Test void failedXidBindingPersistsEmptyOriginalAndWaitsForRealRollbackEvidence() {
        var f=new Fixture("ENT-EMPTY");f.submit();
        db.execute("ALTER TABLE allocation_tc_binding ADD CONSTRAINT ck_test_xid_binding CHECK(enterprise_id<>'ENT-EMPTY')");
        try {f.step();} finally {db.execute("ALTER TABLE allocation_tc_binding DROP CHECK ck_test_xid_binding");}
        assertEquals("FINISH_REQUESTED",f.state());assertEquals("ROLLBACK",f.value("requested_action"));assertEquals(f.xid,f.value("xid"));
        assertNull(db.queryForObject("SELECT xid FROM allocation_attempt WHERE id=?",String.class,f.attempt));
        assertEquals(f.xid,db.queryForObject("SELECT xid FROM allocation_launch WHERE attempt_id=?",String.class,f.attempt));
        assertEquals("PENDING",db.queryForObject("SELECT cleanup_state FROM allocation_launch WHERE attempt_id=?",String.class,f.attempt));
        f.step();assertEquals(1,f.rollbacks.get());assertEquals(0,f.tries.get());
        assertNull(db.queryForObject("SELECT cleanup_terminal_evidence FROM allocation_launch WHERE attempt_id=?",String.class,f.attempt));
        f.proof=proof(f.xid,"Rollbacked",11);f.step();assertEquals("ROLLED_BACK",f.state());
        assertEquals("CLEANED",db.queryForObject("SELECT cleanup_state FROM allocation_launch WHERE attempt_id=?",String.class,f.attempt));
        assertNotNull(db.queryForObject("SELECT cleanup_terminal_evidence FROM allocation_launch WHERE attempt_id=?",String.class,f.attempt));
        assertEquals("CANCELLED",db.queryForObject("SELECT state FROM allocation_attempt WHERE id=?",String.class,f.attempt));
        assertEquals(1,f.begins.get());
    }

    private static TcStatusPort.Observation proof(String xid,String status,int code) {
        return new TcStatusPort.Observation(status,RuntimeMessage.JSON.writeValueAsString(Map.of("xid",xid,"status",code,
                "clusterId",SCOPE.clusterId(),"applicationId",SCOPE.applicationId(),"transactionGroup",SCOPE.transactionGroup())));
    }
    private static final class Fixture implements AllocationTmPort,WarehouseTryPort,TcStatusPort {
        final String e,order,attempt,xid;
        final AtomicInteger begins=new AtomicInteger(),tries=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger();
        boolean beginUnknown,commitUnknown,tryUnknown,stealTry;Observation proof;
        Fixture(String enterprise) {
            e=enterprise;
            try(var session=sessions.openSession(false)) {
                var service=new FulfillmentService(session,CLOCK);
                order=service.createOrder(e,"OMS",e,"a".repeat(64),List.of(Map.of("sourceLineId","LINE","skuId","SKU","requestedQty",new BigDecimal("2"),"baseUnit","EA")),1,"OWNER").get("id").toString();
                attempt=service.prepareAttempt(e,order,"PREPARE",Instant.now().plusSeconds(600),List.of("A","B"),List.of(plan("A"),plan("B"))).get("id").toString();session.commit();
            }
            xid="fixture:"+attempt;
        }
        AllocationExecutionService service(){return new AllocationExecutionService(sessions,SCOPE,CLOCK);}
        AllocationExecutionWorker worker(){return new AllocationExecutionWorker(sessions,this,this,this,SCOPE,CLOCK);}
        void submit(){assertEquals("READY",service().submit(e,order,attempt,"EXECUTE","operator",requests()).get("state"));}
        void step(){db.update("UPDATE allocation_execution SET next_at=UTC_TIMESTAMP(6),lease_until=NULL WHERE attempt_id=?",attempt);assertTrue(worker().executeOne(e));}
        List<WarehouseTryRequest> requests(){return List.of(request("A"),request("B"));}
        WarehouseTryRequest request(String wh){return new WarehouseTryRequest(1,e,wh,"OWNER",attempt,attempt,"CELL-"+wh,1,
                List.of(new WarehouseTryRequest.Line("LINE","SKU","LOC","NO_LOT",BigDecimal.ONE,"EA",0)));}
        Object value(String field){return db.queryForMap("SELECT * FROM allocation_execution WHERE attempt_id=?",attempt).get(field);}
        String state(){return value("state").toString();}
        /** NOWAIT证明外部调用不持有原attempt锁；夹具只读不模拟提交效果。 */
        void assertNoBusinessLock(){
            try(var session=sessions.openSession(false)) {
                try(var stmt=session.getConnection().prepareStatement("SELECT id FROM allocation_attempt WHERE id=? FOR UPDATE NOWAIT")) {stmt.setString(1,attempt);try(var rows=stmt.executeQuery()){assertTrue(rows.next());}}
                session.rollback();
            }catch(Exception locked){throw new AssertionError("网络调用期间不应持有业务锁",locked);}
        }
        @Override public String begin(String id,int timeout){assertNoBusinessLock();assertEquals(attempt,id);begins.incrementAndGet();if(beginUnknown)throw AllocationExecutionService.error("TC_BEGIN_UNKNOWN");return xid;}
        @Override public void commit(String id){assertNoBusinessLock();assertEquals(xid,id);commits.incrementAndGet();if(commitUnknown)throw AllocationExecutionService.error("TC_COMMIT_UNKNOWN");}
        @Override public void rollback(String id){assertNoBusinessLock();assertEquals(xid,id);rollbacks.incrementAndGet();}
        @Override public WarehouseTryResult reserve(String id,WarehouseTryRequest request){
            assertNoBusinessLock();assertEquals(xid,id);assertEquals(request(request.warehouseId()),request);tries.incrementAndGet();
            if(tryUnknown)throw AllocationExecutionService.error("RM_TRY_UNKNOWN");
            if(stealTry)db.update("UPDATE allocation_execution SET claim_epoch=claim_epoch+1 WHERE attempt_id=?",attempt);
            return new WarehouseTryResult(xid,request.warehouseId().equals("A")?1:2,"fixture-action","RES-"+request.warehouseId(),1,attempt,attempt,"TRIED");
        }
        @Override public Optional<Observation> read(String id){assertNoBusinessLock();assertEquals(xid,id);return Optional.ofNullable(proof);}
        void confirm(){try(var session=sessions.openSession(false)) {var service=new FulfillmentService(session,CLOCK);for(String wh:List.of("A","B")) {
            service.observeParticipant(e,attempt,wh,"CONFIRMED",1L);session.getMapper(FulfillmentMapper.class).bindConfirmedAllocation(e,attempt,wh,attempt,Timestamp.from(Instant.now()));}session.commit();}}
        static Map<String,Object> plan(String wh){return Map.of("warehouseId",wh,"orderLineId","LINE","skuId","SKU","qty",BigDecimal.ONE,"baseUnit","EA");}
    }
}
