package com.lrj.wms.inventory.inventory;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.*;
import com.lrj.wms.inventory.effect.infrastructure.EffectMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.runtime.db.DatabaseInstants;
import com.lrj.wms.runtime.db.RuntimeDataSources;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证原订单行/尝试、并发发运和超过200分批仍可有界推进。 */
class OutboundReservationPostingIT {
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;
    private static final Clock CLOCK = Clock.systemUTC();
    @BeforeAll static void setup() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory"); mysql.start();
        var ds = new com.mysql.cj.jdbc.MysqlDataSource(); ds.setUrl(RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        ds.setUser(mysql.getUsername()); ds.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(ds).locations("filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath()).load().migrate();
        var config = new Configuration(new Environment("outbound-posting-it", new JdbcTransactionFactory(), ds));
        DatabaseInstants.configure(config);
        for (var mapper : List.of(MasterdataMapper.class, InventoryMapper.class, OutboxMapper.class,
                CommandDedupMapper.class, EffectMapper.class, StockCommandMapper.class,com.lrj.wms.inventory.serial.LocalSerialMapper.class,com.lrj.wms.inventory.serial.SerialOutboundMapper.class,
                com.lrj.wms.inventory.serial.SerialShipmentMapper.class,com.lrj.wms.inventory.serial.SerialRecoveryMapper.class,
                com.lrj.wms.inventory.serial.SerialReleaseMapper.class,com.lrj.wms.inventory.count.CountSerialMapper.class)) config.addMapper(mapper);
        sessions = new SqlSessionFactoryBuilder().build(config); jdbc = new JdbcTemplate(ds);
        try (var session = sessions.openSession(false)) {
            var md = new MasterdataService(session, CLOCK); md.createWarehouse("WH", "ENT", "WH", "隔离仓", "UTC");
            md.createLocation("SOURCE", "GATE-S", "ENT", "WH", "SOURCE", "A", "STORAGE", new BigDecimal("1000"), "EA");
            md.createLocation("STAGE", "GATE-T", "ENT", "WH", "STAGE", "A", "STAGING", new BigDecimal("1000"), "EA");
            session.commit();
        }
    }
    @AfterAll static void close() { if (mysql != null) mysql.stop(); }

    @Test void wrongIdentityUnconfirmedAndChangedReplayCannotBorrowReservation() {
        seed("IDENTITY", 8, false);
        assertFailure("RESERVATION_NOT_CONFIRMED", "IDENTITY", "PENDING-PICK", "PICK", "LINE", 1);
        try (var session = sessions.openSession(false)) {
            new InventoryApplicationService(session, CLOCK).confirmTried("ENT", "WH", "CFM-IDENTITY", "DOC", "fixture",
                    "ALLOC-IDENTITY", "ATT-IDENTITY", "xid-IDENTITY", 1L, "ReservationTccAction"); session.commit();
        }
        assertFailure("RESERVATION_LINE_INSUFFICIENT", "IDENTITY", "WRONG-LINE", "PICK", "UNKNOWN", 1);
        post("IDENTITY", "PICK-ID", "PICK", "LINE", 3);
        assertFailure("COMMAND_CONFLICT", "IDENTITY", "PICK-ID", "PICK", "OTHER", 3);
        try (var session = sessions.openSession(false)) {
            var error = assertThrows(InventoryException.class, () -> command(session, "IDENTITY", "PICK-ID", "PICK", "LINE", 3,
                    new StockPostingContext("DOC", "OWNER", "IDENTITY", "EA", "SOURCE", "STAGE", "NO_LOT", "GOOD", "OTHER-ALLOC", "ATT-IDENTITY")));
            assertEquals("COMMAND_CONFLICT", error.code()); session.rollback();
        }
        post("IDENTITY", "CANCEL-PART", "CANCEL", "LINE", 2);
        amount("SELECT remaining_qty FROM reservation_line WHERE order_line_id='OTHER' AND reservation_id=(SELECT id FROM reservation WHERE allocation_id='ALLOC-IDENTITY')", "5");
        amount("SELECT SUM(remaining_qty) FROM reservation_line WHERE order_line_id='LINE' AND parent_line_id IS NULL AND reservation_id=(SELECT id FROM reservation WHERE allocation_id='ALLOC-IDENTITY')", "3");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_command WHERE command_id IN ('PENDING-PICK','WRONG-LINE')", Integer.class));
    }

    @Test void competingShipmentsAndFinalPostingFailurePreserveAtomicity() throws Exception {
        seed("RACE", 3, true); post("RACE", "RACE-PICK", "PICK", "LINE", 3);
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                start.await();
                try { post("RACE", "RACE-SHIP-" + i, "SHIP", "LINE", 2); return true; }
                catch (InventoryException failure) { assertEquals("RESERVATION_LINE_INSUFFICIENT", failure.code()); return false; }
            })).toList(); start.countDown();
            int successes = 0; for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally { executor.shutdownNow(); }
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='RACE' AND location_id='STAGE'", "1");
        jdbc.execute("ALTER TABLE stock_posting ADD CONSTRAINT ck_it_final_posting CHECK(command_id <> 'RACE-LAST')");
        try (var session = sessions.openSession(false)) {
            assertThrows(RuntimeException.class, () -> command(session, "RACE", "RACE-LAST", "SHIP", "LINE", 1, context("RACE", "SHIP")));
            session.rollback();
        }
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='RACE' AND location_id='STAGE'", "1");
        jdbc.execute("ALTER TABLE stock_posting DROP CHECK ck_it_final_posting"); post("RACE", "RACE-LAST", "SHIP", "LINE", 1);
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='RACE' AND location_id='STAGE'", "0");
    }

    @Test void moreThanTwoHundredPickedPartsCanBeConsumedWithoutPermanentBlocking() {
        seed("PAGED", 201, true);
        try (var session = sessions.openSession(false)) {
            for (int i = 0; i < 201; i++) command(session, "PAGED", "PAGE-PICK-" + i, "PICK", "LINE", 1, context("PAGED", "PICK"));
            session.commit();
        }
        assertFailure("RESERVATION_BATCH_LIMIT", "PAGED", "PAGE-TOO-LARGE", "SHIP", "LINE", 201);
        post("PAGED", "PAGE-SHIP-1", "SHIP", "LINE", 200); post("PAGED", "PAGE-SHIP-2", "SHIP", "LINE", 1);
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='PAGED' AND location_id='STAGE'", "0");
        amount("SELECT remaining_qty FROM reservation_line WHERE order_line_id='OTHER' AND reservation_id=(SELECT id FROM reservation WHERE allocation_id='ALLOC-PAGED')", "5");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_command WHERE command_id='PAGE-TOO-LARGE'", Integer.class));
    }

    @Test void serialPickMovesExactEpochAndFinalIdentityFailureRollsBackAllQuantities() {
        seed("SERIAL",5,true);
        try(var session=sessions.openSession(false)) {
            var inventory=session.getMapper(InventoryMapper.class);var bucket=inventory.lockBalanceByDimension("ENT","WH","OWNER","SOURCE","SERIAL","NO_LOT","GOOD");
            var locals=session.getMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class);var now=java.sql.Timestamp.from(CLOCK.instant());
            // 明确的本地已登记身份夹具；真实登记和消息进程另有验收，不用此夹具声称全球授权。
            for(int n=1;n<=10;n++) {locals.insertIgnore(java.util.UUID.randomUUID().toString(),"ENT","WH","SERIAL-"+n,"SERIAL","NO_LOT",bucket.get("id").toString(),"AUTHORIZED","ORIGINAL-RECEIPT","ACTIVE",null,now);locals.updateState("ENT","WH","SERIAL-"+n,bucket.get("id").toString(),"AUTHORIZED","ACTIVE",null,1L,now);}session.commit();
        }
        try(var session=sessions.openSession(false)) {
            assertEquals("SERIAL_PICK_CONFLICT",assertThrows(InventoryException.class,() -> serialCommand(session,"SERIAL-WRONG-EPOCH","LINE",new com.lrj.wms.contract.messaging.SerialExecutionSelection(1,List.of(new com.lrj.wms.contract.messaging.SerialExecutionSelection.Identity("SERIAL-1",0))))).code());session.rollback();
        }
        try(var session=sessions.openSession(false)) {serialCommand(session,"SERIAL-FIRST","LINE",serialSelection("SERIAL-1","SERIAL-2"));session.commit();}
        try(var session=sessions.openSession(false)) {serialCommand(session,"SERIAL-FIRST","LINE",serialSelection("serial-2","serial-1"));session.commit();}
        try(var session=sessions.openSession(false)) {assertEquals("COMMAND_CONFLICT",assertThrows(InventoryException.class,() -> serialCommand(session,"SERIAL-FIRST","LINE",serialSelection("SERIAL-1","SERIAL-3"))).code());session.rollback();}
        try(var session=sessions.openSession(false)) {assertEquals("SERIAL_PICK_CONFLICT",assertThrows(InventoryException.class,() -> serialCommand(session,"SERIAL-OTHER-LINE","OTHER",serialSelection("SERIAL-1"))).code());session.rollback();}
        jdbc.execute("ALTER TABLE serial_pick_fact ADD CONSTRAINT fail_last_pick_identity CHECK(command_id<>'SERIAL-LAST' OR serial_id<>'SERIAL-4')");
        try {try(var session=sessions.openSession(false)) {assertThrows(RuntimeException.class,() -> serialCommand(session,"SERIAL-LAST","LINE",serialSelection("SERIAL-3","SERIAL-4")));session.rollback();}}
        finally {jdbc.execute("ALTER TABLE serial_pick_fact DROP CHECK fail_last_pick_identity");}
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL' AND location_id='SOURCE'","8");
        amount("SELECT reserved_qty FROM stock_balance WHERE sku_id='SERIAL' AND location_id='SOURCE'","8");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM serial_pick_fact WHERE command_id='SERIAL-LAST'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM local_serial s JOIN stock_balance b ON b.id=s.balance_id WHERE s.sku_id='SERIAL' AND b.location_id='STAGE'",Integer.class));
        try(var session=sessions.openSession(false)) {serialCommand(session,"SERIAL-LAST","LINE",serialSelection("SERIAL-3","SERIAL-4"));session.commit();}
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL' AND location_id='STAGE'","4");
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM serial_pick_fact WHERE sku_id='SERIAL' AND order_line_id='LINE' AND owner_epoch=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='SERIAL-FIRST'",Integer.class));
        verifySerialShipmentAndRecovery();
    }

    /** 数量/身份/发运意图共同回滚；登记端口在此为故障夹具，真实HTTP另有进程验收。 */
    private static void verifySerialShipmentAndRecovery() {
        try(var session=sessions.openSession(false)) {
            assertThrows(InventoryException.class,() -> serialShip(session,"SERIAL-WRONG-LINE","OTHER",serialSelection("SERIAL-1")));session.rollback();
        }
        jdbc.execute("ALTER TABLE serial_shipment_intent ADD CONSTRAINT fail_last_ship_identity CHECK(command_id<>'SERIAL-SHIP' OR serial_id<>'SERIAL-2')");
        try {try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,() -> serialShip(session,"SERIAL-SHIP","LINE",serialSelection("SERIAL-1","SERIAL-2")));session.rollback();
        }} finally {jdbc.execute("ALTER TABLE serial_shipment_intent DROP CHECK fail_last_ship_identity");}
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL' AND location_id='STAGE'","4");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent",Integer.class));
        try(var session=sessions.openSession(false)) {serialShip(session,"SERIAL-SHIP","LINE",serialSelection("SERIAL-1","SERIAL-2"));session.commit();}
        try(var session=sessions.openSession(false)) {serialShip(session,"SERIAL-SHIP","LINE",serialSelection("serial-2","serial-1"));session.commit();}
        try(var session=sessions.openSession(false)) {
            assertThrows(InventoryException.class,() -> serialShip(session,"SERIAL-DOUBLE-SHIP","LINE",serialSelection("SERIAL-1")));session.rollback();
        }
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL' AND location_id='STAGE'","2");
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE state='SHIPPED' AND registry_state='ACTIVE'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_posting WHERE command_id='SERIAL-SHIP'",Integer.class));
        var calls=new java.util.concurrent.ConcurrentHashMap<String,Integer>();
        com.lrj.wms.inventory.serial.SerialShipmentRegistryPort registry=(e,sku,sn,w,ref,epoch) -> {
            calls.merge(sn,1,Integer::sum);
            // 从独立连接可见已提交事实，远程期间不保留发运业务事务。
            assertEquals("SHIPPED",jdbc.queryForObject("SELECT state FROM local_serial WHERE serial_id=?",String.class,sn));
            return shipmentProof(e,w,sku,sn,ref,epoch);
        };
        jdbc.execute("ALTER TABLE serial_shipment_intent ADD CONSTRAINT fail_last_ship_proof CHECK(serial_id<>'SERIAL-2' OR state<>'DONE')");
        try {
            var first=new com.lrj.wms.inventory.serial.SerialShipmentRecoveryService(sessions,CLOCK,registry).execute("ENT","WH");
            assertEquals(1,first.completed());assertEquals(1,first.failed());
        } finally {jdbc.execute("ALTER TABLE serial_shipment_intent DROP CHECK fail_last_ship_proof");}
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT registry_state FROM local_serial WHERE serial_id='SERIAL-2'",String.class));
        var later=Clock.offset(CLOCK,java.time.Duration.ofSeconds(20));
        assertEquals(1,new com.lrj.wms.inventory.serial.SerialShipmentRecoveryService(sessions,later,registry).execute("ENT","WH").completed());
        assertEquals(1,calls.get("SERIAL-1"));assertEquals(2,calls.get("SERIAL-2"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent WHERE state='DONE' AND result_json IS NOT NULL",Integer.class));
        // 错误证明不完成；人工重排审计失败时不能只解锁意图。
        try(var session=sessions.openSession(false)) {serialShip(session,"SERIAL-SHIP-3","LINE",serialSelection("SERIAL-3"));session.commit();}
        com.lrj.wms.inventory.serial.SerialShipmentRegistryPort wrong=(e,sku,sn,w,ref,epoch) -> shipmentProof(e,w,sku,sn,ref,epoch+1);
        assertEquals(1,new com.lrj.wms.inventory.serial.SerialShipmentRecoveryService(sessions,CLOCK,wrong).execute("ENT","WH").failed());
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT registry_state FROM local_serial WHERE serial_id='SERIAL-3'",String.class));
        jdbc.update("UPDATE serial_shipment_intent SET state='ISOLATED' WHERE serial_id='SERIAL-3'");
        String intent=jdbc.queryForObject("SELECT id FROM serial_shipment_intent WHERE serial_id='SERIAL-3'",String.class);
        long epoch=jdbc.queryForObject("SELECT claim_epoch FROM serial_shipment_intent WHERE id=?",Long.class,intent);
        jdbc.execute("ALTER TABLE serial_shipment_intent ADD CONSTRAINT fail_ship_requeue CHECK(serial_id<>'SERIAL-3' OR state='ISOLATED')");
        try {try(var session=sessions.openSession(false)) {
            assertThrows(RuntimeException.class,() -> com.lrj.wms.inventory.serial.SerialRecoveryOperations.retry(session,CLOCK,"ENT","WH",intent,"SHIP-RETRY","operator",epoch,"已核对原发运事实"));session.rollback();
        }} finally {jdbc.execute("ALTER TABLE serial_shipment_intent DROP CHECK fail_ship_requeue");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM serial_recovery_audit WHERE command_id='SHIP-RETRY'",Integer.class));
        try(var session=sessions.openSession(false)) {
            com.lrj.wms.inventory.serial.SerialRecoveryOperations.retry(session,CLOCK,"ENT","WH",intent,"SHIP-RETRY","operator",epoch,"已核对原发运事实");session.commit();
        }
        var started=new CountDownLatch(1);var resume=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        com.lrj.wms.inventory.serial.SerialShipmentRegistryPort blocked=(e,sku,sn,w,ref,ownerEpoch) -> {
            started.countDown();try {if(!resume.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");}
            catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
            return shipmentProof(e,w,sku,sn,ref,ownerEpoch);
        };
        try {
            var old=executor.submit(() -> new com.lrj.wms.inventory.serial.SerialShipmentRecoveryService(sessions,CLOCK,blocked).execute("ENT","WH"));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            assertEquals(1,new com.lrj.wms.inventory.serial.SerialShipmentRecoveryService(sessions,later,registry).execute("ENT","WH").completed());
            long version=jdbc.queryForObject("SELECT version FROM serial_shipment_intent WHERE id=?",Long.class,intent);
            resume.countDown();assertEquals(0,old.get(5,TimeUnit.SECONDS).completed());
            assertEquals(version,jdbc.queryForObject("SELECT version FROM serial_shipment_intent WHERE id=?",Long.class,intent));
        } catch(Exception failure) {throw new AssertionError(failure);} finally {resume.countDown();executor.shutdownNow();}
        amount("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SERIAL' AND location_id='STAGE'","1");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM serial_shipment_intent WHERE state='DONE'",Integer.class));
    }
    private static Map<String,Object> shipmentProof(String e,String w,String sku,String sn,String ref,long epoch) {
        return Map.of("shipment",Map.of("schemaVersion",1,"enterpriseId",e,"warehouseId",w,"skuId",sku,"normalizedSerial",sn,"ownerEpoch",epoch,"shipmentRef",ref));
    }
    private static Map<String,Object> serialShip(SqlSession session,String command,String line,com.lrj.wms.contract.messaging.SerialExecutionSelection selection) {
        return new StockCommandService(session,CLOCK).applyOutbound("ENT","WH",command,"SHIP","ORDER-SERIAL",command,"INTERNAL-LINE","actor","EXEC-"+command,line,
                context("SERIAL","SHIP"),Quantity.parse(Integer.toString(selection.identities().size()),0),null,selection);
    }
    private static com.lrj.wms.contract.messaging.SerialExecutionSelection serialSelection(String... serials) {
        return new com.lrj.wms.contract.messaging.SerialExecutionSelection(1,java.util.Arrays.stream(serials).map(sn -> new com.lrj.wms.contract.messaging.SerialExecutionSelection.Identity(sn,1L)).toList());
    }
    private static Map<String,Object> serialCommand(SqlSession session,String command,String line,com.lrj.wms.contract.messaging.SerialExecutionSelection selection) {
        return new StockCommandService(session,CLOCK).applyOutbound("ENT","WH",command,"PICK","ORDER-SERIAL",command,"INTERNAL-LINE","actor","EXEC-"+command,line,
                context("SERIAL","PICK"),Quantity.parse(Integer.toString(selection.identities().size()),0),null,selection);
    }

    private static void seed(String sku, int qty, boolean confirmed) {
        try (var session = sessions.openSession(false)) {
            var app = new InventoryApplicationService(session, CLOCK);
            var bucket = StockBucketKey.of("ENT", "WH", "OWNER", "SOURCE", sku, "NO_LOT", "GOOD");
            app.receive("ENT", "WH", "RECEIVE-" + sku, "DOC", "fixture", bucket, Quantity.parse(Integer.toString(qty + 5), 0));
            app.reserveTried("ENT", "WH", "TRY-" + sku, "DOC", "fixture", "ALLOC-" + sku, "ATT-" + sku, "xid-" + sku,
                    1L, "ReservationTccAction", 1L, "d".repeat(64), List.of(new ReservationLineInput(bucket, Quantity.parse(Integer.toString(qty), 0), "LINE"),
                    new ReservationLineInput(bucket, Quantity.parse("5", 0), "OTHER")));
            if (confirmed) app.confirmTried("ENT", "WH", "CONFIRM-" + sku, "DOC", "fixture", "ALLOC-" + sku, "ATT-" + sku, "xid-" + sku, 1L, "ReservationTccAction");
            session.commit();
        }
    }
    private static StockPostingContext context(String sku, String action) {
        return new StockPostingContext("DOC", "OWNER", sku, "EA", action.equals("SHIP") ? "STAGE" : "SOURCE", action.equals("PICK") ? "STAGE" : null,
                "NO_LOT", "GOOD", "ALLOC-" + sku, "ATT-" + sku);
    }
    private static Map<String, Object> command(SqlSession session, String sku, String id, String action, String line, int qty, StockPostingContext context) {
        return new StockCommandService(session, CLOCK).applyOutbound("ENT", "WH", id, action, "ORDER-" + sku, id, "INTERNAL-LINE",
                "actor", "EXEC-" + id, line, context, Quantity.parse(Integer.toString(qty), 0), null);
    }
    private static void post(String sku, String id, String action, String line, int qty) {
        try (var session = sessions.openSession(false)) { command(session, sku, id, action, line, qty, context(sku, action)); session.commit(); }
    }
    private static void assertFailure(String code, String sku, String id, String action, String line, int qty) {
        try (var session = sessions.openSession(false)) {
            var failure = assertThrows(InventoryException.class, () -> command(session, sku, id, action, line, qty, context(sku, action)));
            assertEquals(code, failure.code()); session.rollback();
        }
    }
    private static void amount(String sql, String expected) { assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(sql, BigDecimal.class))); }
}
