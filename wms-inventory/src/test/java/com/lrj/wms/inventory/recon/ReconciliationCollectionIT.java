package com.lrj.wms.inventory.recon;

import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.serial.SerialRecoveryMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL证明租约、检查点和审计原子性；尚不代表远程采集接线完成。 */
class ReconciliationCollectionIT {
    private static final Instant NOW=Instant.parse("2026-09-13T00:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    @BeforeAll static void prepare() {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory").withUsername("wms").withPassword(UUID.randomUUID().toString());mysql.start();
        var source=new MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
        source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var config=new Configuration(new Environment("collection",new JdbcTransactionFactory(),source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(InventoryMapper.class);config.addMapper(ReconciliationMapper.class);
        config.addMapper(ReconciliationCollectionMapper.class);config.addMapper(SerialRecoveryMapper.class);
        config.addMapper(SnapshotMapper.class);
        sessions=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup() {if(mysql!=null) mysql.stop();}
    private static ReconciliationCollectionStore store(SqlSession session,Instant time) {return new ReconciliationCollectionStore(session,Clock.fixed(time,ZoneOffset.UTC));}
    private static long epoch(Map<String,Object> row) {return ((Number)row.get("claim_epoch")).longValue();}

    /** 来源端口夹具提供原事实；本用例实际验证库存匹配、反向集合和最终数据库事务。 */
    @Test void collectorMatchesBothSourcesAndRecoversFailedFinalProofWrite() throws Exception {
        String warehouse="FINAL-FAIL";
        var inbound=sourceFact("IN-CMD");
        var outbound=sourceFact("OUT-CMD");
        insertOriginal(warehouse,"wms-inbound",inbound,NOW.minusSeconds(90));
        insertOriginal(warehouse,"wms-outbound",outbound,NOW.minusSeconds(90));
        var port=port(Map.of("wms-inbound",List.of(inbound),"wms-outbound",List.of(outbound)));
        try(var session=sessions.openSession(false);var sql=session.getConnection().createStatement()) {
            sql.execute("ALTER TABLE reconciliation_cutoff ADD CONSTRAINT test_final_proof CHECK(evidence_version=0 OR warehouse_id<>'FINAL-FAIL' OR claim_epoch>6)");
        }
        var collector=new ReconciliationCollector(sessions,Clock.fixed(NOW,ZoneOffset.UTC),port);
        collector.request("E",warehouse,"C",NOW.minusSeconds(60),"operator");
        for(int n=0;n<6;n++) collector.advance("E",warehouse,"C");
        try(var session=sessions.openSession(false)) {
            var row=session.getMapper(ReconciliationCollectionMapper.class).lock("E",warehouse,"C");
            assertEquals(0,((Number)row.get("evidence_version")).intValue());
            assertEquals("PENDING",row.get("collection_state"));assertEquals("SOURCE_UNAVAILABLE",row.get("collection_error"));
        }
        new ReconciliationCollector(sessions,Clock.fixed(NOW.plusSeconds(3),ZoneOffset.UTC),port).advance("E",warehouse,"C");
        try(var session=sessions.openSession(false)) {
            var row=session.getMapper(ReconciliationCollectionMapper.class).lock("E",warehouse,"C");
            assertEquals("COMPLETE",row.get("collection_state"));assertEquals(1,((Number)row.get("evidence_version")).intValue());
            assertEquals(64,String.valueOf(row.get("source_watermark")).length());
            assertNull(session.getMapper(ReconciliationCollectionMapper.class).guard("E",warehouse).get("active_cutoff_id"));
            var exported=new SnapshotExportService(session,Clock.fixed(NOW.plusSeconds(3),ZoneOffset.UTC)).export("E",warehouse,"C",
                    java.sql.Timestamp.from(NOW.minusSeconds(60)),(String)row.get("source_watermark"),(String)row.get("posting_watermark"),(String)row.get("receipt_watermark"));
            assertEquals("COMPLETE",exported.get("state"));
        }
    }

    @Test void latePostingAndExtraInventoryMemberNeverMintProof() throws Exception {
        for(String warehouse:List.of("LATE","EXTRA")) {
            var fact=sourceFact(warehouse+"-CMD");
            insertOriginal(warehouse,"wms-inbound",fact,"LATE".equals(warehouse)?NOW.minusSeconds(30):NOW.minusSeconds(90));
            var port=port(Map.of("wms-inbound","LATE".equals(warehouse)?List.of(fact):List.of(),"wms-outbound",List.of()));
            var collector=new ReconciliationCollector(sessions,Clock.fixed(NOW,ZoneOffset.UTC),port);
            collector.request("E",warehouse,"C",NOW.minusSeconds(60),"operator");
            for(int n=0;n<3;n++) collector.advance("E",warehouse,"C");
            try(var session=sessions.openSession(false)) {
                var row=session.getMapper(ReconciliationCollectionMapper.class).lock("E",warehouse,"C");
                assertEquals(0,((Number)row.get("evidence_version")).intValue());
                assertEquals("SOURCE_INCOMPLETE",row.get("collection_error"));
            }
        }
    }
    private static com.lrj.wms.runtime.messaging.SourceWindowService.Fact sourceFact(String id) {
        return new com.lrj.wms.runtime.messaging.SourceWindowService.Fact(id,"SHIP","EX-"+id,"1","1","P-"+id,NOW.minusSeconds(120).toString(),"APPLIED");
    }
    private static ReconciliationSourcePort port(Map<String,List<com.lrj.wms.runtime.messaging.SourceWindowService.Fact>> facts) {
        return new ReconciliationSourcePort() {
            @Override public Collection collect(String source,String e,String w,String id,Instant cutoff) {return new Collection(true,facts.get(source).size());}
            @Override public com.lrj.wms.runtime.messaging.SourceWindowService.Page read(String source,String e,String w,String id,Instant cutoff,String cursor) {
                String digest=com.lrj.wms.runtime.messaging.SourceWindowService.initialDigest(source,e,w,id,cutoff);
                for(var fact:facts.get(source)) digest=com.lrj.wms.runtime.messaging.SourceWindowService.append(digest,fact);
                return new com.lrj.wms.runtime.messaging.SourceWindowService.Page(1,source,e,w,id,cutoff.toString(),facts.get(source).size(),digest,facts.get(source),null);
            }
        };
    }
    private static void insertOriginal(String w,String source,com.lrj.wms.runtime.messaging.SourceWindowService.Fact fact,Instant posted) throws Exception {
        try(var session=sessions.openSession(false)) {
            try(var sql=session.getConnection().prepareStatement("INSERT INTO stock_command(id,enterprise_id,warehouse_id,source_service,command_id,action,business_effect_key,execution_attempt_id,attempt_no,payload_digest,digest_version,state,created_at,updated_at) VALUES(?,'E',?,?,?,'SHIP',?,?,1,?,1,'APPLIED',?,?)")) {
                Object[] args={fact.commandId(),w,source,fact.commandId(),"EFF-"+fact.commandId(),"ATT-"+fact.commandId(),"a".repeat(64),java.sql.Timestamp.from(posted),java.sql.Timestamp.from(posted)};
                for(int n=0;n<args.length;n++) sql.setObject(n+1,args[n]);sql.executeUpdate();
            }
            try(var sql=session.getConnection().prepareStatement("INSERT INTO stock_posting(id,enterprise_id,warehouse_id,source_service,command_id,business_effect_key,action,execution_attempt_id,posting_type,quantity,source_execution_id,source_document_id,ledger_manifest,result_version,created_at,updated_at) VALUES(?,'E',?,?,?,?,'SHIP',?,'SHIP',1,?,'DOC','{}',0,?,?)")) {
                Object[] args={fact.postingId(),w,source,fact.commandId(),"EFF-"+fact.commandId(),"ATT-"+fact.commandId(),fact.executionId(),java.sql.Timestamp.from(posted),java.sql.Timestamp.from(posted)};
                for(int n=0;n<args.length;n++) sql.setObject(n+1,args[n]);sql.executeUpdate();
            }
            session.commit();
        }
    }

    @Test void restartTakesLeaseAndRejectsOldReplyWhileKeepingSingleActiveWindow() {
        try(var session=sessions.openSession(false)) {
            var store=store(session,NOW);
            assertEquals("PENDING",store.request("E","W","C",NOW.minusSeconds(60),"{\"page\":0}","operator").get("collection_state"));
            assertEquals("{\"page\": 0}",store.request("E","W","C",NOW.minusSeconds(60),"{\"page\":99}","operator").get("collection_progress"));
            session.commit();
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("WINDOW_ACTIVE",assertThrows(JobRunException.class,() -> store(session,NOW).request("E","W","OTHER",NOW.minusSeconds(30),"{}","operator")).code());
        }
        long old;
        try(var session=sessions.openSession(false)) {old=epoch(store(session,NOW).claim("E","W","C"));session.commit();}
        try(var session=sessions.openSession(false)) {assertNull(store(session,NOW.plusSeconds(1)).claim("E","W","C"));}
        long current;
        try(var session=sessions.openSession(false)) {current=epoch(store(session,NOW.plusSeconds(16)).claim("E","W","C"));session.commit();}
        assertTrue(current>old);
        try(var session=sessions.openSession(false)) {
            var store=store(session,NOW.plusSeconds(17));
            assertFalse(store.checkpoint("E","W","C",old,"{\"page\":999}",true,null));
            assertTrue(store.checkpoint("E","W","C",current,"{\"page\":1}",true,null));session.commit();
        }
        try(var session=sessions.openSession(false)) {
            var row=session.getMapper(ReconciliationCollectionMapper.class).lock("E","W","C");
            assertEquals(0,((Number)row.get("collection_attempts")).intValue());
            assertEquals(0,((Number)row.get("evidence_version")).intValue());
            assertEquals(1,com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.readTree(String.valueOf(row.get("collection_progress"))).path("page").asInt());
            store(session,NOW.plusSeconds(18)).control("E","W","C",current,"CANCEL","operator","停止此窗口");session.commit();
        }
        try(var session=sessions.openSession(false)) {
            var guard=session.getMapper(ReconciliationCollectionMapper.class).guard("E","W");
            assertNull(guard.get("active_cutoff_id"));assertNotNull(guard.get("closed_before"));
            assertFalse(store(session,NOW.plusSeconds(19)).checkpoint("E","W","C",current,"{}",true,null));
        }
    }

    @Test void twelveFailuresIsolateAndRetryAuditFailureRollsBackEpoch() throws Exception {
        try(var session=sessions.openSession(false)) {store(session,NOW).request("E","RETRY","C",NOW.minusSeconds(60),"{}","operator");session.commit();}
        long epoch=0;
        for(int n=0;n<12;n++) {
            Instant time=NOW.plusSeconds(n*301L);
            try(var session=sessions.openSession(false)) {
                var store=store(session,time);epoch=epoch(store.claim("E","RETRY","C"));
                assertTrue(store.checkpoint("E","RETRY","C",epoch,"{}",false,"SOURCE_PENDING"));session.commit();
            }
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("ISOLATED",session.getMapper(ReconciliationCollectionMapper.class).lock("E","RETRY","C").get("collection_state"));
            try(var statement=session.getConnection().createStatement()) {statement.execute("ALTER TABLE reconciliation_collection_audit ADD CONSTRAINT test_recon_audit CHECK(reason<>'FAIL')");}
        }
        final long isolatedEpoch=epoch;
        try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,() -> store(session,NOW.plusSeconds(4000)).control("E","RETRY","C",isolatedEpoch,"RETRY","operator","FAIL"));
        }
        try(var session=sessions.openSession(false)) {
            var row=session.getMapper(ReconciliationCollectionMapper.class).lock("E","RETRY","C");
            assertEquals(isolatedEpoch,epoch(row));assertEquals("ISOLATED",row.get("collection_state"));
            store(session,NOW.plusSeconds(4000)).control("E","RETRY","C",isolatedEpoch,"RETRY","operator","修复来源后重排");session.commit();
        }
        try(var session=sessions.openSession(false)) {
            var claimed=store(session,NOW.plusSeconds(4001)).claim("E","RETRY","C");
            assertEquals(isolatedEpoch+2,epoch(claimed));
            assertEquals(1,((Number)claimed.get("collection_attempts")).intValue());session.commit();
        }
    }
}
