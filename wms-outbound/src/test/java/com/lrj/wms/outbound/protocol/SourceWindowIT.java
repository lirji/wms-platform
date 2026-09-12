package com.lrj.wms.outbound.protocol;

import com.lrj.wms.runtime.messaging.SourceWindowService;
import com.lrj.wms.runtime.messaging.persistence.SourceWindowMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL关窗/在途事务/分页回滚；T3状态是明确夹具，实际消息另有进程测试。 */
class SourceWindowIT {
    private static final Instant BASE=Instant.parse("2026-09-13T00:00:00Z"),CUTOFF=BASE.plusSeconds(10);
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;
    @BeforeAll static void prepare() {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("source_window").withUsername("wms").withPassword(UUID.randomUUID().toString());mysql.start();
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
        source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);
        var config=new Configuration(new Environment("window",new JdbcTransactionFactory(),source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);config.addMapper(SourceMapper.class);config.addMapper(SourceWindowMapper.class);
        sessions=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void close() {if(mysql!=null) mysql.stop();}

    @Test void persistedPagesWaitForReceiptAndRollbackWithoutLosingOriginalDigest() {
        try(var session=sessions.openSession(false)) {
            for(int i=0;i<201;i++) submit(session,"PAGE",String.format("PAGE-%03d",i),BASE);
            session.commit();
        }
        posted("PAGE",200);
        try(var session=sessions.openSession(false)) {
            var window=service(session).collect("ENT","PAGE","CUT",CUTOFF);
            assertEquals("COLLECTING",window.get("state"));assertEquals(200L,((Number)window.get("fact_count")).longValue());session.commit();
        }
        try(var session=sessions.openSession(false)) {
            var waiting=service(session).collect("ENT","PAGE","CUT",CUTOFF);
            assertEquals(200L,((Number)waiting.get("fact_count")).longValue());
            assertThrows(IllegalStateException.class,() -> service(session).read("ENT","PAGE","CUT",CUTOFF,null));session.commit();
        }
        posted("PAGE",201);
        jdbc.execute("ALTER TABLE source_reconciliation_window ADD CONSTRAINT fail_window_final CHECK(state<>'COMPLETE')");
        try(var session=sessions.openSession(false)) {assertThrows(RuntimeException.class,() -> service(session).collect("ENT","PAGE","CUT",CUTOFF));session.rollback();}
        assertEquals(200L,jdbc.queryForObject("SELECT fact_count FROM source_reconciliation_window WHERE warehouse_id='PAGE'",Long.class));
        jdbc.execute("ALTER TABLE source_reconciliation_window DROP CHECK fail_window_final");
        try(var session=sessions.openSession(false)) {
            var complete=service(session).collect("ENT","PAGE","CUT",CUTOFF);assertEquals("COMPLETE",complete.get("state"));session.commit();
            var first=service(session).read("ENT","PAGE","CUT",CUTOFF,null);assertEquals(200,first.facts().size());assertNotNull(first.nextCursor());
            var last=service(session).read("ENT","PAGE","CUT",CUTOFF,first.nextCursor());assertEquals(1,last.facts().size());assertNull(last.nextCursor());
            String digest=SourceWindowService.initialDigest("wms-outbound","ENT","PAGE","CUT",CUTOFF);
            for(var fact:first.facts()) digest=SourceWindowService.append(digest,fact);
            digest=SourceWindowService.append(digest,last.facts().getFirst());assertEquals(first.digest(),digest);assertEquals(201,first.factCount());
            assertThrows(IllegalArgumentException.class,() -> service(session).read("ENT","OTHER","CUT",CUTOFF,null));
            assertThrows(IllegalArgumentException.class,() -> service(session).collect("ENT","PAGE","CUT",CUTOFF.plusSeconds(1)));session.rollback();
        }
    }

    @Test void freezeWaitsForInFlightT1AndRejectsBackdatedNewCommand() throws Exception {
        try(var writer=sessions.openSession(false);var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            submit(writer,"LOCK","LOCK-1",BASE);
            var started=new java.util.concurrent.CountDownLatch(1);
            var closure=pool.submit(() -> {try(var session=sessions.openSession(false)) {started.countDown();
                var result=service(session).collect("ENT","LOCK","CUT",CUTOFF);session.commit();return result;}});
            assertTrue(started.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> closure.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
            writer.commit();assertEquals("COLLECTING",closure.get(5,java.util.concurrent.TimeUnit.SECONDS).get("state"));
        }
        try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,() -> submit(session,"LOCK","LOCK-OLD",BASE.plusSeconds(1)));session.rollback();
            submit(session,"LOCK","LOCK-NEW",CUTOFF.plusSeconds(1));session.commit();
            submit(session,"UNLOCKED","OTHER-OLD",BASE);session.commit();
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM source_command WHERE command_id='LOCK-OLD'",Integer.class));
    }
    private static SourceWindowService service(org.apache.ibatis.session.SqlSession session) {return new SourceWindowService(session,Clock.fixed(CUTOFF.plusSeconds(30),ZoneOffset.UTC),"wms-outbound");}
    private static void submit(org.apache.ibatis.session.SqlSession session,String warehouse,String command,Instant when) {
        new SourceProtocolService(session,Clock.fixed(when,ZoneOffset.UTC)).submitShip("ENT",warehouse,command,"ORDER",command,"LINE","fixture",BigDecimal.ONE);
    }
    private static void posted(String warehouse,int count) {
        jdbc.update("UPDATE source_command SET state='APPLIED',posting_id=CONCAT('POST-',command_id),payload_json=JSON_SET(payload_json,'$.postingContext',JSON_OBJECT('fixture',true)) WHERE warehouse_id=? ORDER BY command_id LIMIT "+count,warehouse);
        jdbc.update("UPDATE source_execution SET posted_qty=physical_qty,stock_sync_status='POSTED' WHERE warehouse_id=? AND command_id IN (SELECT command_id FROM source_command WHERE warehouse_id=? AND state='APPLIED')",warehouse,warehouse);
    }
}
