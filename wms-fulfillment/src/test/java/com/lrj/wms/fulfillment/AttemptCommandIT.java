package com.lrj.wms.fulfillment;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证响应丢失、并发占键及最后写回执失败的原子性。 */
class AttemptCommandIT {
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate db;
    private static final Instant NOW=Instant.parse("2026-09-13T00:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    @BeforeAll static void setup() {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("attempt_command").withPassword(UUID.randomUUID().toString());mysql.start();
        var source=new com.mysql.cj.jdbc.MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
        source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());db=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load().migrate();
        var config=new Configuration(new Environment("attempt-command",new JdbcTransactionFactory(),source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);config.addMapper(FulfillmentMapper.class);
        sessions=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup() {if(mysql!=null)mysql.stop();}

    @Test void lostResponseKeepsOriginalAttemptAndDeadlineEvenAfterNewAttempt() {
        String e="ENT-REPLAY",order=order(e,"SO");
        Map<String,Object> first=prepare(e,order,"KEY",null,plans("1"),CLOCK);
        var replay=prepare(e,order,"KEY",null,plans("1.000000"),Clock.offset(CLOCK,Duration.ofDays(1)));
        assertEquals(first.get("id"),replay.get("id"));assertEquals(first.get("deadline"),replay.get("deadline"));
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",assertThrows(FulfillmentException.class,
                ()->prepare(e,order,"KEY",NOW.plusSeconds(3600),plans("1"),CLOCK)).code());
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",assertThrows(FulfillmentException.class,
                ()->prepare(e,order,"KEY",null,plans("2"),CLOCK)).code());
        String other=order(e,"OTHER");
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",assertThrows(FulfillmentException.class,
                ()->prepare(e,other,"KEY",null,plans("1"),CLOCK)).code());
        // 明确的历史终态夹具，证明旧命令不能被后来活动attempt替换。
        db.update("UPDATE allocation_attempt SET state='FAILED' WHERE id=?",first.get("id"));
        var next=prepare(e,order,"NEXT",null,plans("1"),CLOCK);
        assertNotEquals(first.get("id"),next.get("id"));
        assertEquals(first.get("id"),prepare(e,order,"KEY",null,plans("1"),CLOCK).get("id"));
    }

    @Test void finalReceiptFailureRollsBackOrderPointerAndAllAttemptRows() {
        String e="ENT-ROLLBACK",order=order(e,"SO");
        db.execute("ALTER TABLE fulfillment_attempt_command ADD CONSTRAINT ck_test_attempt_receipt CHECK(enterprise_id<>'ENT-ROLLBACK' OR attempt_id IS NULL)");
        try {
            var failure=assertThrows(RuntimeException.class,()->prepare(e,order,"KEY",null,plans("1"),CLOCK));
            assertTrue(failure.getMessage().contains("ck_test_attempt_receipt"),failure.getMessage());
            assertNull(db.queryForObject("SELECT active_attempt_id FROM fulfillment_order WHERE id=?",String.class,order));
            for(String table:List.of("fulfillment_attempt_command","allocation_attempt","allocation_participant","participant_line"))
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE enterprise_id=?",Integer.class,e));
        } finally {db.execute("ALTER TABLE fulfillment_attempt_command DROP CHECK ck_test_attempt_receipt");}
        assertNotNull(prepare(e,order,"KEY",null,plans("1"),CLOCK).get("id"));
    }

    @Test void concurrentRetriesCreateOneAttemptWithOneReceipt() throws Exception {
        String e="ENT-RACE",order=order(e,"SO");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
            Callable<Object> command=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));return prepare(e,order,"KEY",null,plans("1"),CLOCK).get("id");};
            var one=pool.submit(command);var two=pool.submit(command);assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            assertEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM allocation_attempt WHERE enterprise_id=?",Integer.class,e));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM fulfillment_attempt_command WHERE enterprise_id=? AND attempt_id IS NOT NULL",Integer.class,e));
    }

    private String order(String e,String source) {
        try(var session=sessions.openSession(false)) {
            String result=String.valueOf(new FulfillmentService(session,CLOCK).createOrder(e,"OMS",source,"a".repeat(64),
                    List.of(Map.of("sourceLineId","LINE","skuId","SKU","requestedQty",BigDecimal.ONE,"baseUnit","EA")),1,"OWNER").get("id"));
            session.commit();return result;
        }
    }
    private Map<String,Object> prepare(String e,String order,String key,Instant deadline,List<Map<String,Object>> lines,Clock clock) {
        try(var session=sessions.openSession(false)) {
            var result=new FulfillmentService(session,clock).prepareAttempt(e,order,key,deadline,List.of("WH"),lines);
            session.commit();return result;
        }
    }
    private static List<Map<String,Object>> plans(String qty) {
        return List.of(Map.of("warehouseId","WH","orderLineId","LINE","skuId","SKU","qty",new BigDecimal(qty),"baseUnit","EA"));
    }
}
