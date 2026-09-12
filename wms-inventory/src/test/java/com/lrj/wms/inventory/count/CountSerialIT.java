package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.serial.LocalSerialMapper;
import com.lrj.wms.inventory.serial.SerialCountRegistryPort;
import com.lrj.wms.inventory.serial.SerialReceiptService;
import com.lrj.wms.inventory.serial.SerialRegistryConflictException;
import com.lrj.wms.inventory.serial.SerialRegistryPort;
import com.lrj.wms.inventory.serial.SerialRegistryUnavailableException;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

/** S6-03a：序列号观察集合与 FOUND/MISSING。不是 AC-18/19 生产。 */
class CountSerialIT {
    private static final Instant NOW = Instant.parse("2026-09-12T04:10:00Z");
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
        config.addMapper(CountMapper.class); config.addMapper(CountSerialMapper.class);
        config.addMapper(LocalSerialMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.SerialRecoveryMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-SN", "GATE-SN", "ENT-1", "WH-A", "S-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-DN", "GATE-DN", "ENT-1", "WH-A", "S-02", "A", "STORAGE", new BigDecimal("100"),
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
    void identitiesAdjustFoundMissingAndRejectQtyOnly() {
        MemoryCountRegistry registry = new MemoryCountRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey hold = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-SN", "SKU-CS", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, registry);
            CountService counts = new CountService(session, clock, registry);
            receipts.receiveHold("ENT-1", "WH-A", "OP-CS-A", "DOC-CS", "ACTOR", "sn-a", hold);
            receipts.receiveHold("ENT-1", "WH-A", "OP-CS-B", "DOC-CS", "ACTOR", "sn-b", hold);
            counts.create("ENT-1", "WH-A", "CP-SN", "CYCLE", List.of("LOC-SN"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-SN");
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-SN");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            String lineId = String.valueOf(lines.getFirst().get("id"));
            InventoryException qtyOnly = assertThrows(InventoryException.class,
                    () -> counts.observe("ENT-1", "WH-A", "CP-SN", lineId, "OBS-Q", "2", "ACTOR", 1));
            assertEquals("SERIAL_SET_REQUIRED", qtyOnly.code());
            InventoryException size = assertThrows(InventoryException.class,
                    () -> counts.observeIdentities("ENT-1", "WH-A", "CP-SN", lineId, "OBS-S", "3", "ACTOR", 1,
                            List.of("sn-a", "sn-c")));
            assertEquals("SERIAL_QTY_MISMATCH", size.code());
            Map<String, Object> observed = counts.observeIdentities("ENT-1", "WH-A", "CP-SN", lineId, "OBS-1", "2",
                    "ACTOR", 1, List.of("sn-a", "sn-c"));
            assertEquals(1, observed.get("found"));
            assertEquals(1, observed.get("missing"));
            counts.submitReview("ENT-1", "WH-A", "CP-SN");
            counts.approve("ENT-1", "WH-A", "CP-SN", "AP-SN", "APPR");
            assertEquals(CountService.LINE_ZERO,
                    counts.applyLine("ENT-1", "WH-A", "CP-SN", lineId, "OP-CS-ADJ", "ACTOR").get("status"));
            assertEquals(CountService.COMPLETED, counts.unfreeze("ENT-1", "WH-A", "CP-SN").get("status"));
            var replay=counts.observeIdentities("ENT-1","WH-A","CP-SN",lineId,"OBS-1","2","ACTOR",1,List.of(" SN-C ","sn-a"));
            assertEquals(1,replay.get("found"));assertEquals(1,replay.get("missing"));
            assertEquals("OBSERVATION_CONFLICT",assertThrows(InventoryException.class,() -> counts.observeIdentities("ENT-1","WH-A","CP-SN",lineId,"OBS-1","2","ACTOR",1,List.of("SN-A","SN-D"))).code());
            assertEquals("OBSERVATION_CONFLICT",assertThrows(InventoryException.class,() -> counts.observe("ENT-1","WH-A","CP-SN",lineId,"OBS-1","2","ACTOR",1)).code());
            InventoryException missingRecover = assertThrows(InventoryException.class,
                    () -> receipts.recover("ENT-1", "WH-A", "sn-b"));
            assertEquals("SERIAL_MISSING", missingRecover.code());
            session.commit();
        }
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-A'", String.class));
        assertEquals("MISSING", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-B'", String.class));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-C'", String.class));
        assertEquals("MISSING", registry.row("ENT-1", "SKU-CS", "SN-B").get("state"));
        assertEquals("ACTIVE", registry.row("ENT-1", "SKU-CS", "SN-C").get("state"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-CS'", BigDecimal.class)
                .compareTo(new BigDecimal("2.000000")));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM count_observation_serial WHERE observation_id='OBS-1'", Integer.class));
    }

    @Test
    void registryDownKeepsLineFrozen() {
        MemoryCountRegistry registry = new MemoryCountRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey hold = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-DN", "SKU-DN", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, registry);
            CountService counts = new CountService(session, clock, registry);
            receipts.receiveHold("ENT-1", "WH-A", "OP-DN-X", "DOC-DN", "ACTOR", "sn-x", hold);
            receipts.receiveHold("ENT-1", "WH-A", "OP-DN-Y", "DOC-DN", "ACTOR", "sn-y", hold);
            counts.create("ENT-1", "WH-A", "CP-DN", "CYCLE", List.of("LOC-DN"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-DN");
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-DN");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            String lineId = String.valueOf(lines.getFirst().get("id"));
            counts.observeIdentities("ENT-1", "WH-A", "CP-DN", lineId, "OBS-DN", "1", "ACTOR", 1, List.of("sn-x"));
            counts.submitReview("ENT-1", "WH-A", "CP-DN");
            counts.approve("ENT-1", "WH-A", "CP-DN", "AP-DN", "APPR");
            registry.available = false;
            InventoryException pending = assertThrows(InventoryException.class,
                    () -> counts.applyLine("ENT-1", "WH-A", "CP-DN", lineId, "OP-DN-ADJ", "ACTOR"));
            assertEquals("COUNT_REGISTRY_PENDING", pending.code());
            InventoryException frozenStill = assertThrows(InventoryException.class,
                    () -> counts.unfreeze("ENT-1", "WH-A", "CP-DN"));
            assertTrue("COUNT_APPLY_PENDING".equals(frozenStill.code())
                    || "COUNT_REGISTRY_PENDING".equals(frozenStill.code()));
            session.commit();
        }
        assertEquals("MISSING_PENDING", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-Y'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-DN'", BigDecimal.class)
                .compareTo(new BigDecimal("2.000000")));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-DN'",
                String.class));
        assertNotEquals("APPLIED", jdbc.queryForObject(
                "SELECT status FROM count_line WHERE count_plan_id='CP-DN'", String.class));
    }

    @Test void emptySeenSetMeansAllMissingAndOldRoundCannotReplaceLatestObservation() {
        var registry=new MemoryCountRegistry();var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        String line=serialCountFixture("EMPTY",registry,clock);
        try(var session=sessions.openSession(false)) {
            var counts=new CountService(session,clock,registry);
            counts.observeIdentities("EMPTY","WH-EMPTY","CP-EMPTY",line,"OBS-EMPTY-1","1","ACTOR",1,List.of("EMPTY-A"));
            var result=counts.observeIdentities("EMPTY","WH-EMPTY","CP-EMPTY",line,"OBS-EMPTY-2","0","ACTOR",2,List.of());
            assertEquals(2,result.get("missing"));assertEquals(0,result.get("found"));
            counts.observeIdentities("EMPTY","WH-EMPTY","CP-EMPTY",line,"OBS-EMPTY-1","1","ACTOR",1,List.of("empty-a"));
            assertEquals("OBSERVATION_CONFLICT",assertThrows(InventoryException.class,() -> counts.observeIdentities("EMPTY","WH-EMPTY","CP-EMPTY",line,"OBS-OTHER","1","ACTOR",2,List.of("EMPTY-A"))).code());
            counts.submitReview("EMPTY","WH-EMPTY","CP-EMPTY");counts.approve("EMPTY","WH-EMPTY","CP-EMPTY","AP-EMPTY","APPROVER");
            assertEquals(CountService.LINE_APPLIED,counts.applyLine("EMPTY","WH-EMPTY","CP-EMPTY",line,"APPLY-EMPTY","ACTOR").get("status"));
            counts.unfreeze("EMPTY","WH-EMPTY","CP-EMPTY");
            assertEquals(2,counts.observeIdentities("EMPTY","WH-EMPTY","CP-EMPTY",line,"OBS-EMPTY-2","0","ACTOR",2,List.of()).get("missing"));session.commit();
        }
        assertEquals(0,jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE enterprise_id='EMPTY'",BigDecimal.class).signum());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE enterprise_id='EMPTY' AND state='MISSING'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM count_observation WHERE enterprise_id='EMPTY'",Integer.class));
    }
    @Test void finalObservationChildFailureRollsBackFullInputAndCurrentCount() {
        var registry=new MemoryCountRegistry();var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        String line=serialCountFixture("INPUT-ROLLBACK",registry,clock);
        jdbc.execute("ALTER TABLE count_observation_serial ADD CONSTRAINT reject_final_sight CHECK(enterprise_id<>'INPUT-ROLLBACK' OR normalized_serial<>'INPUT-ROLLBACK-B')");
        try {
            try(var session=sessions.openSession(false)) {
                assertThrows(RuntimeException.class,() -> new CountService(session,clock,registry).observeIdentities("INPUT-ROLLBACK","WH-INPUT-ROLLBACK","CP-INPUT-ROLLBACK",line,"OBS-ROLLBACK","0","ACTOR",1,List.of()));session.rollback();
            }
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM count_observation WHERE enterprise_id='INPUT-ROLLBACK'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM count_observation_serial WHERE enterprise_id='INPUT-ROLLBACK'",Integer.class));
            assertNull(jdbc.queryForObject("SELECT counted_qty FROM count_line WHERE id=?",BigDecimal.class,line));
            assertEquals("FROZEN",jdbc.queryForObject("SELECT status FROM count_plan WHERE enterprise_id='INPUT-ROLLBACK'",String.class));
        } finally {jdbc.execute("ALTER TABLE count_observation_serial DROP CHECK reject_final_sight");}
        try(var session=sessions.openSession(false)) {
            new CountService(session,clock,registry).observeIdentities("INPUT-ROLLBACK","WH-INPUT-ROLLBACK","CP-INPUT-ROLLBACK",line,"OBS-ROLLBACK","0","ACTOR",1,List.of());session.commit();
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM count_observation_serial WHERE enterprise_id='INPUT-ROLLBACK'",Integer.class));
        // 旧行不具备完整输入证明，不能通过一次重放给既有子行补上新解释。
        jdbc.update("UPDATE count_observation SET observation_kind=NULL,serial_input_json=NULL WHERE enterprise_id='INPUT-ROLLBACK'");
        try(var session=sessions.openSession(false)) {
            assertEquals("OBSERVATION_CONFLICT",assertThrows(InventoryException.class,() -> new CountService(session,clock,registry).observeIdentities("INPUT-ROLLBACK","WH-INPUT-ROLLBACK","CP-INPUT-ROLLBACK",line,"OBS-ROLLBACK","0","ACTOR",1,List.of())).code());session.rollback();
        }
    }
    @Test void presentReceiptMustAuthorizeBeforeAdjustmentSnapshotWithoutBurningRowBudget() {
        var registry=new MemoryCountRegistry();var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        String e="PRESENT",w="WH-PRESENT",plan="CP-PRESENT",line=serialCountFixture(e,registry,clock);
        jdbc.update("UPDATE local_serial SET state='HOLD_RECEIVED',registry_state='NONE',owner_epoch=0 WHERE enterprise_id=? AND serial_id='PRESENT-A'",e);
        try(var session=sessions.openSession(false)) {
            var counts=new CountService(session,clock);counts.observeIdentities(e,w,plan,line,"OBS-PRESENT","1","counter",1,List.of("PRESENT-A"));
            counts.submitReview(e,w,plan);counts.approve(e,w,plan,"APPROVAL-PRESENT","approver");session.commit();
        }
        var waiting=new CountApplyRecovery(sessions,clock).execute(e,w,plan);assertEquals(0,waiting.applied());assertEquals(0,waiting.failed());
        assertEquals(0,jdbc.queryForObject("SELECT recovery_attempts FROM count_line WHERE id=?",Integer.class,line));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM count_adjustment_intent WHERE enterprise_id=?",Integer.class,e));
        // 协议夹具模拟原收货恢复完成；真实登记由ProcessesIT覆盖。
        jdbc.update("UPDATE local_serial SET state='AUTHORIZED',registry_state='ACTIVE',owner_epoch=1 WHERE enterprise_id=? AND serial_id='PRESENT-A'",e);
        try(var session=sessions.openSession(false)) {
            var staged=new CountSerialAdjustmentService(session,Clock.fixed(NOW.plusSeconds(40),ZoneOffset.UTC)).stage(e,w,plan,line,"PRESENT-OP","operator");
            assertEquals("PENDING",staged.get("state"));session.commit();
        }
    }
    @Test void lateIdentityReplyCannotReapplyAfterLeaseTakeoverAndUnfreeze() throws Exception {
        var registry=new MemoryCountRegistry();var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        String e="LEASE",w="WH-LEASE",plan="CP-LEASE",line=serialCountFixture(e,registry,clock);
        try(var session=sessions.openSession(false)) {
            var counts=new CountService(session,clock);counts.observeIdentities(e,w,plan,line,"OBS-LEASE","1","counter",1,List.of("LEASE-A"));
            counts.submitReview(e,w,plan);counts.approve(e,w,plan,"APPROVAL-LEASE","approver");
            new CountSerialAdjustmentService(session,clock).stage(e,w,plan,line,"ORIGINAL-LEASE","operator");session.commit();
        }
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        SerialCountRegistryPort port=new SerialCountRegistryPort() {
            public Map<String,Object> markMissing(String enterprise,String sku,String sn,String warehouse,String ref,long epoch) {
                if(calls.incrementAndGet()==1) {entered.countDown();try {if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("接管未完成");} catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
                return Map.of("normalizedSerial",sn,"ownerWarehouseId",warehouse,"receiptOperationId",ref,"ownerEpoch",epoch,"state","MISSING");
            }
            public Map<String,Object> claimFound(String e,String sku,String sn,String w,String op) {throw new AssertionError("不应盘盈");}
            public Map<String,Object> activateFound(String e,String sku,String sn,String w,String op) {throw new AssertionError("不应盘盈");}
            public Map<String,Object> get(String e,String sku,String sn) {throw new AssertionError("不能用当前查询代替原动作凭证");}
        };
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var old=executor.submit(() -> new CountApplyRecovery(sessions,clock,port).execute(e,w,plan));
            try {
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var later=Clock.fixed(NOW.plusSeconds(20),ZoneOffset.UTC);
                var takeover=new CountApplyRecovery(sessions,later,port).execute(e,w,plan);
                assertEquals(1,takeover.applied());assertEquals(0,takeover.failed());
                try(var session=sessions.openSession(false)) {new CountService(session,later).unfreeze(e,w,plan);session.commit();}
            } finally {release.countDown();}
            var stale=old.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,stale.applied());assertEquals(0,stale.failed());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE enterprise_id='LEASE' AND operation_id='ORIGINAL-LEASE'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE enterprise_id='LEASE'",BigDecimal.class).compareTo(BigDecimal.ONE));
        assertEquals(2L,jdbc.queryForObject("SELECT claim_epoch FROM count_serial_intent WHERE enterprise_id='LEASE'",Long.class));
    }
    @SuppressWarnings("unchecked")
    private String serialCountFixture(String e,MemoryCountRegistry registry,Clock clock) {
        try(var session=sessions.openSession(false)) {
            String w="WH-"+e,location="LOC-"+e;
            var master=new MasterdataService(session,clock);master.createWarehouse(w,e,e,e,"UTC");
            master.createLocation(location,"GATE-"+e,e,w,e,"A","STORAGE",new BigDecimal("100"),"EA");
            var bucket=StockBucketKey.of(e,w,"OWNER",location,"SKU-"+e,"NO_LOT","HOLD");
            var receipts=new SerialReceiptService(session,clock,registry);
            receipts.receiveHold(e,w,"RECEIPT-A","DOC","ACTOR",e+"-A",bucket);receipts.receiveHold(e,w,"RECEIPT-B","DOC","ACTOR",e+"-B",bucket);
            var counts=new CountService(session,clock,registry);counts.create(e,w,"CP-"+e,"CYCLE",List.of(location));counts.startQuiescing(e,w,"CP-"+e);
            var result=counts.freeze(e,w,"CP-"+e);session.commit();return ((List<Map<String,Object>>)result.get("lines")).getFirst().get("id").toString();
        }
    }

    static final class MemoryCountRegistry implements SerialRegistryPort, SerialCountRegistryPort {
        private final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();
        volatile boolean available = true;

        @Override
        public synchronized Map<String, Object> claim(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            String key = key(enterpriseId, skuId, serial);
            Map<String, Object> existing = rows.get(key);
            if (existing != null) {
                if ("MISSING".equals(existing.get("state"))) {
                    throw new SerialRegistryConflictException("SERIAL_MISSING", "失踪序列号不能按首次认领占用");
                }
                if (!operationId.equals(existing.get("claimOperationId"))) {
                    throw new SerialRegistryConflictException("SERIAL_ALREADY_CLAIMED", "序列号已被其他操作认领");
                }
                return copy(existing);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", UUID.randomUUID().toString());
            row.put("state", "CLAIMED");
            row.put("normalizedSerial", SerialReceiptService.normalize(serial));
            row.put("ownerWarehouseId", warehouseId);
            row.put("ownerEpoch", 1L);
            row.put("claimOperationId", operationId);
            rows.put(key, row);
            return copy(row);
        }

        @Override
        public synchronized Map<String, Object> activate(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未认领");
            }
            if ("MISSING".equals(row.get("state"))) {
                throw new SerialRegistryConflictException("SERIAL_MISSING", "失踪序列号不能按原认领激活");
            }
            if (!warehouseId.equals(row.get("ownerWarehouseId"))) {
                throw new SerialRegistryConflictException("SERIAL_OWNER_MISMATCH", "激活仓与登记归属不一致");
            }
            if (!operationId.equals(row.get("claimOperationId"))) {
                throw new SerialRegistryConflictException("SERIAL_OPERATION_MISMATCH", "激活操作与认领不一致");
            }
            row.put("state", "ACTIVE");
            return copy(row);
        }

        @Override
        public synchronized Map<String, Object> markMissing(String enterpriseId, String skuId, String serial,
                String warehouseId, String factRef, long expectedEpoch) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未登记");
            }
            if ("MISSING".equals(row.get("state")) && factRef.equals(row.get("factRef"))) {
                return copy(row);
            }
            if (expectedEpoch != ((Number) row.get("ownerEpoch")).longValue()) {
                throw new SerialRegistryConflictException("STALE_EPOCH", "失踪事实代际不匹配");
            }
            if (!"ACTIVE".equals(row.get("state")) || !warehouseId.equals(row.get("ownerWarehouseId"))) {
                throw new SerialRegistryConflictException("SERIAL_STATE_CONFLICT", "当前登记状态不能标失踪");
            }
            row.put("state", "MISSING");
            row.put("factRef", factRef);
            return copy(row);
        }

        @Override
        public synchronized Map<String, Object> claimFound(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            Map<String, Object> existing = rows.get(key(enterpriseId, skuId, serial));
            if (existing == null) {
                return claim(enterpriseId, skuId, serial, warehouseId, operationId);
            }
            if ("FOUND_CLAIMED".equals(existing.get("state")) && operationId.equals(existing.get("claimOperationId"))) {
                return copy(existing);
            }
            if ("ACTIVE".equals(existing.get("state"))) {
                throw new SerialRegistryConflictException("SERIAL_ALREADY_CLAIMED", "序列号仍是有效授权，不能盘盈认领");
            }
            if (!"MISSING".equals(existing.get("state"))) {
                throw new SerialRegistryConflictException("SERIAL_STATE_CONFLICT", "当前登记状态不能盘盈认领");
            }
            existing.put("state", "FOUND_CLAIMED");
            existing.put("ownerWarehouseId", warehouseId);
            existing.put("ownerEpoch", ((Number) existing.get("ownerEpoch")).longValue() + 1);
            existing.put("claimOperationId", operationId);
            return copy(existing);
        }

        @Override
        public synchronized Map<String, Object> activateFound(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未认领");
            }
            if ("ACTIVE".equals(row.get("state")) && warehouseId.equals(row.get("ownerWarehouseId"))) {
                return copy(row);
            }
            if (!"FOUND_CLAIMED".equals(row.get("state"))) {
                return activate(enterpriseId, skuId, serial, warehouseId, operationId);
            }
            if (!operationId.equals(row.get("claimOperationId"))) {
                throw new SerialRegistryConflictException("SERIAL_OPERATION_MISMATCH", "盘盈激活操作与认领不一致");
            }
            row.put("state", "ACTIVE");
            return copy(row);
        }

        @Override
        public Map<String, Object> get(String enterpriseId, String skuId, String serial) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未登记");
            }
            return copy(row);
        }

        Map<String, Object> row(String enterpriseId, String skuId, String serial) {
            return copy(rows.get(key(enterpriseId, skuId, serial)));
        }

        private void requireAvailable() {
            if (!available) {
                throw new SerialRegistryUnavailableException("登记服务不可用");
            }
        }

        private static String key(String enterpriseId, String skuId, String serial) {
            return enterpriseId + '|' + skuId + '|' + SerialReceiptService.normalize(serial);
        }

        private static Map<String, Object> copy(Map<String, Object> row) {
            return row == null ? null : new LinkedHashMap<>(row);
        }
    }
}
