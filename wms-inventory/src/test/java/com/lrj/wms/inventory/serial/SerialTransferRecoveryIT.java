package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S6-04：源仓已封闭但登记未见释放时目的保持 HOLD，恢复后不二次扣源仓。不是 AC-17 生产。 */
class SerialTransferRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:10:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(LocalSerialMapper.class); config.addMapper(SerialReleaseMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.SerialRecoveryMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-S", "GATE-S", "ENT-1", "WH-A", "S-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createWarehouse("WH-B", "ENT-1", "NGB", "宁波仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-B", "GATE-B", "ENT-1", "WH-B", "B-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void sealedSourceWithoutReleaseKeepsDestinationHoldAndReplayDoesNotRededuct() {
        SerialReceiptIT.MemoryRegistry claims = new SerialReceiptIT.MemoryRegistry();
        SerialSealIT.MemoryTransferRegistry registry = new SerialSealIT.MemoryTransferRegistry();
        registry.blockUntilRelease();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey source = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-S", "SKU-RV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        StockBucketKey dest = StockBucketKey.of("ENT-1", "WH-B", "OWNER-1", "LOC-B", "SKU-RV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, claims);
            SerialTransferLocalService transfers = new SerialTransferLocalService(session, clock, registry);
            receipts.receiveHold("ENT-1", "WH-A", "OP-RV-S", "DOC-RV", "ACTOR", "sn-rv", source);
            assertEquals(SerialTransferLocalService.STATE_SEALED,
                    transfers.sealSource("ENT-1", "WH-A", "sn-rv", "TR-RV", 1, "REL-RV").get("state"));
            Map<String, Object> held = transfers.receiveDestination("ENT-1", "WH-B", "OP-RV-D", "DOC-RV", "ACTOR",
                    "sn-rv", dest, "TR-RV", 1);
            assertEquals(SerialReceiptService.STATE_HOLD_RECEIVED, held.get("state"));
            session.commit();
        }
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-A' AND serial_id='SN-RV'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-RV'", BigDecimal.class)
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-B' AND sku_id='SKU-RV'", BigDecimal.class)
                .compareTo(new BigDecimal("1.000000")));

        registry.releaseToTransit();
        try (SqlSession session = sessions.openSession(false)) {
            SerialTransferLocalService transfers = new SerialTransferLocalService(session, clock, registry);
            assertEquals(SerialReceiptService.STATE_AUTHORIZED,
                    transfers.receiveDestination("ENT-1", "WH-B", "OP-RV-D", "DOC-RV", "ACTOR", "sn-rv", dest, "TR-RV",
                            1).get("state"));
            assertEquals(SerialTransferLocalService.STATE_SEALED,
                    transfers.sealSource("ENT-1", "WH-A", "SN-RV", "TR-RV", 1, "REL-RV").get("state"));
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='REL-RV'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-RV'", BigDecimal.class)
                .compareTo(BigDecimal.ZERO));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-B' AND serial_id='SN-RV'", String.class));
    }
    @Test void sourceReleaseRetryBudgetIsolationAndLeaseRejectLateWorker() throws Exception {
        Clock initial=Clock.fixed(NOW.plusSeconds(1000),ZoneOffset.UTC);
        try(var session=sessions.openSession(false)) {
            var master=new MasterdataService(session,initial);
            master.createWarehouse("WH-BUDGET","ENT-BUDGET","BUDGET","预算测试仓","UTC");
            master.createLocation("LOC-BUDGET","GATE-BUDGET","ENT-BUDGET","WH-BUDGET","BUDGET","A","STORAGE",new BigDecimal("100"),"EA");
            var bucket=StockBucketKey.of("ENT-BUDGET","WH-BUDGET","OWNER-1","LOC-BUDGET","SKU-BUDGET",MasterdataCodes.NO_LOT,"HOLD");
            new SerialReceiptService(session,initial,new SerialReceiptIT.MemoryRegistry()).receiveHold("ENT-BUDGET","WH-BUDGET","OP-BUDGET","DOC","ACTOR","SN-BUDGET",bucket);
            session.commit();
        }
        jdbc.update("UPDATE location_gate SET state='FROZEN' WHERE enterprise_id='ENT-BUDGET'");
        try(var session=sessions.openSession(false)) {
            var error=assertThrows(com.lrj.wms.inventory.inventory.InventoryException.class,() -> new SerialTransferLocalService(session,initial,null).sealSource("ENT-BUDGET","WH-BUDGET","SN-BUDGET","TR-BUDGET",1,"REL-BUDGET"));
            assertEquals("STOCK_FROZEN",error.code());session.rollback();
        }
        assertEquals("AUTHORIZED",jdbc.queryForObject("SELECT state FROM local_serial WHERE enterprise_id='ENT-BUDGET'",String.class));
        jdbc.update("UPDATE location_gate SET state='OPEN' WHERE enterprise_id='ENT-BUDGET'");
        try(var session=sessions.openSession(false)) {
            new SerialTransferLocalService(session,initial,null).sealSource("ENT-BUDGET","WH-BUDGET","SN-BUDGET","TR-BUDGET",1,"REL-BUDGET");session.commit();
        }
        SerialReleaseRegistryPort unavailable=(e,sku,sn,tr,w,ref,epoch) -> {throw new SerialRegistryUnavailableException("注入断连");};
        for(int n=0;n<12;n++) {
            var clock=Clock.fixed(NOW.plusSeconds(1000+n*360L),ZoneOffset.UTC);
            assertEquals(1,new SerialReleaseRecoveryService(sessions,clock,unavailable).execute("ENT-BUDGET","WH-BUDGET").failed());
        }
        assertEquals("ISOLATED",jdbc.queryForObject("SELECT state FROM serial_release_intent WHERE serial_id='SN-BUDGET'",String.class));
        Clock retryTime=Clock.fixed(NOW.plusSeconds(10000),ZoneOffset.UTC);
        assertEquals(0,new SerialReleaseRecoveryService(sessions,retryTime,unavailable).execute("ENT-BUDGET","WH-BUDGET").failed());
        String id=jdbc.queryForObject("SELECT id FROM serial_release_intent WHERE serial_id='SN-BUDGET'",String.class);
        try(var session=sessions.openSession(false)) {
            SerialRecoveryOperations.retry(session,retryTime,"ENT-BUDGET","WH-BUDGET",id,"RETRY-BUDGET","operator",12,"已核实原释放事实");session.commit();
        }
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        SerialReleaseRegistryPort proof=(e,sku,sn,tr,w,ref,epoch) -> Map.of("sourceRelease",Map.of("enterpriseId",e,"sourceWarehouseId",w,"skuId",sku,"normalizedSerial",sn,"transferId",tr,"sourceReleaseRef",ref,"fromEpoch",epoch));
        SerialReleaseRegistryPort late=(e,sku,sn,tr,w,ref,epoch) -> {
            entered.countDown();try {assertTrue(resume.await(10,java.util.concurrent.TimeUnit.SECONDS));}
            catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new RuntimeException(ex);}
            return proof.release(e,sku,sn,tr,w,ref,epoch);
        };
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var old=worker.submit(() -> new SerialReleaseRecoveryService(sessions,retryTime,late).execute("ENT-BUDGET","WH-BUDGET"));
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            // 第一执行器不持有数据库连接/锁，租约超时后新执行器可以正常领取完成。
            assertEquals(1,new SerialReleaseRecoveryService(sessions,Clock.fixed(retryTime.instant().plusSeconds(20),ZoneOffset.UTC),proof).execute("ENT-BUDGET","WH-BUDGET").completed());
            resume.countDown();assertEquals(0,old.get(3,java.util.concurrent.TimeUnit.SECONDS).completed());
        } finally {resume.countDown();worker.shutdownNow();}
        assertEquals(15L,jdbc.queryForObject("SELECT claim_epoch FROM serial_release_intent WHERE id=?",Long.class,id));
        assertEquals("DONE",jdbc.queryForObject("SELECT state FROM serial_release_intent WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='REL-BUDGET'",Integer.class));
    }

}
