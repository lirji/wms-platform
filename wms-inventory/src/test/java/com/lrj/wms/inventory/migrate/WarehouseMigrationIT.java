package com.lrj.wms.inventory.migrate;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
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
import java.util.Map;
import java.util.UUID;
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

/** S9-02：两物理库全量+增量、停写、切 epoch、旧库拒写。不是生产停写窗口验收。 */
class WarehouseMigrationIT {
    private static MySQLContainer sourceMysql;
    private static MySQLContainer targetMysql;
    private static SqlSessionFactory sourceSessions;
    private static SqlSessionFactory targetSessions;
    private static JdbcTemplate sourceJdbc;
    private static JdbcTemplate targetJdbc;

    @BeforeAll
    static void prepare() {
        sourceMysql = mysql("wms_source");
        targetMysql = mysql("wms_target");
        sourceMysql.start();
        targetMysql.start();
        MysqlDataSource source = datasource(sourceMysql);
        MysqlDataSource target = datasource(targetMysql);
        new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", "").initialize(source, () -> Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate());
        new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", "").initialize(target, () -> Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate());
        sourceJdbc = new JdbcTemplate(source);
        targetJdbc = new JdbcTemplate(target);
        sourceSessions = sessions("source", source);
        targetSessions = sessions("target", target);
        Clock clock = Clock.systemUTC();
        try (SqlSession session = sourceSessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-FULL", "DOC", "ACTOR",
                    bucket(), Quantity.parse("10", 0));
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (sourceMysql != null) {
            sourceMysql.stop();
        }
        if (targetMysql != null) {
            targetMysql.stop();
        }
    }

    @Test
    void localRmCompletionCannotReplaceTcTerminalProofForMigration() {
        Clock clock=Clock.systemUTC();
        try(var session=sourceSessions.openSession(false)) {
            var master=new MasterdataService(session,clock);
            master.createWarehouse("WH-RM","ENT-RM","RM","RM仓","UTC");
            master.createLocation("LOC-RM","GATE-RM","ENT-RM","WH-RM","RM-LOC","A","STORAGE",new BigDecimal("100"),"EA");
            new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock).prepare("ENT-RM","WH-RM","CELL-A","CELL-B");session.commit();
        }
        // 明确的本地已Confirm夹具；即便本地成功也不能凭此判定TC已收妥回执。
        sourceJdbc.update("INSERT INTO inventory_tcc_intent(id,enterprise_id,warehouse_id,allocation_id,attempt_id,xid,action_name,cell_id,route_epoch,request_digest,request_payload,branch_id,state,created_at,updated_at) VALUES('RM-INTENT','ENT-RM','WH-RM','ALLOC','ATT','fixture-xid','fixture-action','CELL-A',1,?,'{}',1,'CONFIRMED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))","a".repeat(64));
        try(var session=sourceSessions.openSession(false)) {
            assertEquals("MIGRATION_TCC_PROOF_REQUIRED",assertThrows(InventoryException.class,()->
                    new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock).quiesce("ENT-RM","WH-RM")).code());
        }
        assertEquals("ACTIVE",sourceJdbc.queryForObject("SELECT state FROM warehouse_route WHERE enterprise_id='ENT-RM'",String.class));
        assertEquals("OPEN",sourceJdbc.queryForObject("SELECT state FROM location_gate WHERE enterprise_id='ENT-RM'",String.class));
    }

    @Test
    void twoPhysicalDatabasesSwitchEpochAndRejectOldWrites() {
        Clock clock = Clock.systemUTC();
        // 明确的迁移夹具：原批次JSON与稳定主键必须复制，不能因新增表没有id游标而漏数。
        sourceJdbc.update("UPDATE reconciliation_history_guard SET closed_before='2026-01-01 00:00:00',version=4 WHERE enterprise_id='ENT-1' AND warehouse_id='WH-A'");
        String observation="{\"schemaVersion\":1,\"serialIds\":[\"SN-A\",\"SN-B\"]}";
        sourceJdbc.update("INSERT INTO serial_receipt_batch(id,enterprise_id,warehouse_id,receipt_command_id,context_hash,observation_json,identity_count,state,created_at,updated_at) VALUES('BATCH-MIGRATION','ENT-1','WH-A','RECEIPT-SERIAL',?,CAST(? AS JSON),2,'APPLIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                "a".repeat(64),observation);
        sourceJdbc.update("INSERT INTO count_observation(id,enterprise_id,warehouse_id,count_plan_id,count_line_id,observation_id,qty,actor_id,round_no,created_at,updated_at,observation_kind,serial_input_json) VALUES('COUNT-INPUT-M','ENT-1','WH-A','PLAN-M','LINE-M','OBS-M',0,'COUNTER',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'SERIAL',CAST(? AS JSON))","{\"schemaVersion\":1,\"serialIds\":[]}");
        // 存储迁移夹具保留逐身份租约、结果和原上下文，不把空表拷贝当作恢复验证。
        sourceJdbc.update("INSERT INTO count_adjustment_intent(id,enterprise_id,warehouse_id,plan_id,line_id,observation_id,operation_id,actor_id,context_json,context_hash,state,created_at,updated_at) VALUES('COUNT-ADJUST-M','ENT-1','WH-A','PLAN-M','LINE-M','OBS-M','COUNT-OP-M','operator',CAST(? AS JSON),?,'PENDING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))","{\"schemaVersion\":1,\"quantity\":\"0\"}","c".repeat(64));
        sourceJdbc.update("INSERT INTO count_serial_intent(id,enterprise_id,warehouse_id,adjustment_id,plan_id,serial_id,sku_id,operation_id,kind,from_epoch,state,result_json,claim_epoch,attempts,next_attempt_at,created_at,updated_at) VALUES('COUNT-SN-M','ENT-1','WH-A','COUNT-ADJUST-M','PLAN-M','SN-A','SKU','COUNT-OP-M','MISSING',3,'DONE',CAST(? AS JSON),7,4,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))","{\"state\":\"MISSING\",\"ownerEpoch\":3}");
        sourceJdbc.update("INSERT INTO serial_pick_fact(id,enterprise_id,warehouse_id,command_id,operation_id,allocation_id,attempt_id,order_line_id,sku_id,serial_id,owner_epoch,source_balance_id,target_balance_id,created_at,updated_at) VALUES('SERIAL-PICK-M','ENT-1','WH-A','PICK-M','OP-M','ALLOC-M','ATT-M','ORDER-LINE-M','SKU','SN-M',3,'SOURCE-B','TARGET-B',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sourceJdbc.update("INSERT INTO serial_release_intent(id,enterprise_id,warehouse_id,serial_id,sku_id,transfer_id,release_ref,from_epoch,context_hash,state,attempts,claim_epoch,next_attempt_at,created_at,updated_at) VALUES('RELEASE-MIGRATION','ENT-1','WH-A','SN-A','SKU','TRANSFER-M','RELEASE-M',3,?,'ISOLATED',12,15,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))","b".repeat(64));
        sourceJdbc.update("INSERT INTO serial_shipment_intent(id,enterprise_id,warehouse_id,command_id,serial_id,sku_id,pick_fact_id,balance_id,shipment_ref,owner_epoch,state,result_json,claim_epoch,attempts,next_attempt_at,created_at,updated_at) VALUES('SHIP-M','ENT-1','WH-A','SHIP-CMD-M','SN-M','SKU','SERIAL-PICK-M','TARGET-B','SHIP-OP-M',3,'DONE',CAST(? AS JSON),9,3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                "{\"schemaVersion\":1,\"enterpriseId\":\"ENT-1\",\"warehouseId\":\"WH-A\",\"skuId\":\"SKU\",\"normalizedSerial\":\"SN-M\",\"ownerEpoch\":3,\"shipmentRef\":\"SHIP-OP-M\"}");
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            assertEquals(ACTIVE_COPY, migrate.prepare("ENT-1", "WH-A", "CELL-A", "CELL-B").get("state"));
            Map<String, Object> full = migrate.copyFull("ENT-1", "WH-A");
            assertTrue(((Number) full.get("copiedRows")).intValue() >= 3);
            session.commit();
        }
        assertEquals(sourceJdbc.queryForMap("SELECT * FROM serial_receipt_batch WHERE id='BATCH-MIGRATION'"),
                targetJdbc.queryForMap("SELECT * FROM serial_receipt_batch WHERE id='BATCH-MIGRATION'"));
        for(String table:java.util.List.of("count_adjustment_intent","count_serial_intent","serial_pick_fact","serial_shipment_intent","reconciliation_history_guard"))
            assertEquals(sourceJdbc.queryForList("SELECT * FROM "+table+" WHERE enterprise_id='ENT-1' AND warehouse_id='WH-A'"),targetJdbc.queryForList("SELECT * FROM "+table+" WHERE enterprise_id='ENT-1' AND warehouse_id='WH-A'"));
        assertEquals(sourceJdbc.queryForMap("SELECT * FROM serial_release_intent WHERE id='RELEASE-MIGRATION'"),
                targetJdbc.queryForMap("SELECT * FROM serial_release_intent WHERE id='RELEASE-MIGRATION'"));
        assertEquals(sourceJdbc.queryForMap("SELECT * FROM count_observation WHERE id='COUNT-INPUT-M'"),
                targetJdbc.queryForMap("SELECT * FROM count_observation WHERE id='COUNT-INPUT-M'"));
        try (SqlSession session = sourceSessions.openSession(false)) {
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-INCR", "DOC", "ACTOR",
                    bucket(), Quantity.parse("2", 0));
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            migrate.copyIncremental("ENT-1", "WH-A");
            assertEquals(0, new BigDecimal("12").compareTo(targetJdbc.queryForObject(
                    "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A'", BigDecimal.class)));
            assertEquals(WarehouseMigrationService.QUIESCING, migrate.quiesce("ENT-1", "WH-A").get("state"));
            assertEquals(Boolean.TRUE, migrate.validate("ENT-1", "WH-A").get("validated"));
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            InventoryException blocked = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-OLD", "DOC",
                            "ACTOR", bucket(), Quantity.parse("1", 0)));
            assertEquals("STALE_ROUTE", blocked.code());
            session.rollback();
        }
        // 两库之间没有原子提交：目标激活后源提交失败，下次必须精确恢复且不重开目标新门禁。
        try (SqlSession session = sourceSessions.openSession(false)) {
            new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock).switchEpoch("ENT-1","WH-A");
            session.rollback();
        }
        assertEquals("QUIESCING",sourceJdbc.queryForObject("SELECT state FROM warehouse_route WHERE warehouse_id='WH-A'",String.class));
        assertEquals("ACTIVE",targetJdbc.queryForObject("SELECT state FROM warehouse_route WHERE warehouse_id='WH-A'",String.class));
        targetJdbc.update("UPDATE location_gate SET state='FROZEN',reason_code='COUNT' WHERE warehouse_id='WH-A'");
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            Map<String, Object> switched = migrate.switchEpoch("ENT-1", "WH-A");
            assertEquals(2L, ((Number) switched.get("switchedEpoch")).longValue());
            assertEquals(WarehouseMigrationService.RETIRED, switched.get("state"));
            InventoryException rollback = assertThrows(InventoryException.class,
                    () -> migrate.refuseRollbackAfterCutover("ENT-1", "WH-A"));
            assertEquals("ROLLBACK_FORBIDDEN", rollback.code());
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            InventoryException retired = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-SRC", "DOC",
                            "ACTOR", bucket(), Quantity.parse("1", 0)));
            assertEquals("STALE_ROUTE", retired.code());
            session.rollback();
        }
        assertEquals("FROZEN",targetJdbc.queryForObject("SELECT state FROM location_gate WHERE warehouse_id='WH-A'",String.class));
        targetJdbc.update("UPDATE location_gate SET state='OPEN',reason_code=NULL WHERE warehouse_id='WH-A'");
        try (SqlSession session = targetSessions.openSession(false)) {
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-TGT", "DOC", "ACTOR",
                    bucket(), Quantity.parse("1", 0));
            session.commit();
        }
        assertEquals(0, new BigDecimal("13").compareTo(targetJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A'", BigDecimal.class)));
        assertEquals("RETIRED", sourceJdbc.queryForObject(
                "SELECT state FROM warehouse_route WHERE warehouse_id='WH-A'", String.class));
        System.out.println("S9_MIGRATE: two MySQL; incremental catch-up; epoch switch; old source writes denied");
    }

    @Test
    void abortBeforeSwitchReopensSourceWrites() {
        Clock clock = Clock.systemUTC();
        try (SqlSession session = sourceSessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-B", "ENT-1", "SHB", "回退仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-B", "GATE-B", "ENT-1", "WH-B", "B-01", "B", "STORAGE",
                    new BigDecimal("50"), "EA");
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-B", "OP-B1", "DOC", "ACTOR",
                    bucketB(), Quantity.parse("4", 0));
            session.commit();
        }
        try (SqlSession session = sourceSessions.openSession(false)) {
            WarehouseMigrationService migrate = new WarehouseMigrationService(session, sourceJdbc, targetJdbc, clock);
            migrate.prepare("ENT-1", "WH-B", "CELL-A", "CELL-B");
            migrate.copyFull("ENT-1", "WH-B");
            migrate.quiesce("ENT-1", "WH-B");
            assertEquals(WarehouseMigrationService.ACTIVE, migrate.abortBeforeSwitch("ENT-1", "WH-B").get("state"));
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-B", "OP-B2", "DOC", "ACTOR",
                    bucketB(), Quantity.parse("1", 0));
            session.commit();
        }
        assertEquals(0, new BigDecimal("5").compareTo(sourceJdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-B'", BigDecimal.class)));
        System.out.println("S9_MIGRATE: abort before cutover reopens source; not a post-cutover rollback");
    }

    @Test void scopedRecoveryRowsCopyCompletelyAndConflictsRollbackWithoutOverwritingOtherWarehouses() {
        var actualTables=new java.util.TreeSet<>(sourceJdbc.queryForList("SELECT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('enterprise_id','warehouse_id') GROUP BY TABLE_NAME HAVING COUNT(DISTINCT COLUMN_NAME)=2",String.class));
        actualTables.remove("warehouse_route");
        assertEquals(actualTables,new java.util.TreeSet<>(com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore.COPY_TABLES),"新增仓表必须显式归入迁移清单；路由单独治理");
        var store=new com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore(sourceJdbc.getDataSource(),targetJdbc.getDataSource());
        var now=java.sql.Timestamp.from(java.time.Instant.parse("2026-09-12T02:03:04.123456Z"));
        store.prepareTarget("ENT-M","WH-M","TARGET","SOURCE",now);
        sourceJdbc.update("INSERT INTO count_plan(id,enterprise_id,warehouse_id,status,reason_code,created_at,updated_at) VALUES('PLAN-M','ENT-M','WH-M','DRAFT','CYCLE',?,?)",now,now);
        sourceJdbc.update("INSERT INTO serial_recovery_intent(id,enterprise_id,warehouse_id,serial_id,sku_id,operation_id,kind,transfer_id,from_epoch,context_hash,state,claim_epoch,attempts,next_attempt_at,created_at,updated_at) VALUES('INTENT-M','ENT-M','WH-M','SN-M','SKU','RECEIPT','TRANSFER','TRANSFER',3,?,'ISOLATED',11,12,?,?,?)","0".repeat(64),now,now,now);
        sourceJdbc.update("INSERT INTO runtime_message_inbox(id,event_key,enterprise_id,warehouse_id,source_service,topic_name,partition_no,offset_no,payload_hash,payload,status,claim_epoch,next_attempt_at,created_at,updated_at) VALUES('INBOX-M',?,'ENT-M','WH-M','wms-inbound','isolated.inbound.commands',0,123,?,?,'ISOLATED',17,?,?,?)","1".repeat(64),"2".repeat(64),"{\"originalCommand\":\"原收货\"}",now,now,now);
        sourceJdbc.update("INSERT INTO serial_recovery_audit(id,enterprise_id,warehouse_id,command_id,intent_id,actor_id,reason,expected_epoch,request_hash,created_at) VALUES('AUDIT-M','ENT-M','WH-M','RETRY','INTENT-M','operator','核对原始凭证',10,?,?)","3".repeat(64),now);
        for(String table:java.util.List.of("count_plan","serial_recovery_intent","runtime_message_inbox","serial_recovery_audit")) {
            store.copyTable(table,"ENT-M","WH-M",null);
            assertEquals(1,store.count(true,table,"ENT-M","WH-M"));
            store.copyTable(table,"ENT-M","WH-M",null);
            assertEquals(1,store.count(true,table,"ENT-M","WH-M"));
        }
        assertEquals(17L,targetJdbc.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE id='INBOX-M'",Long.class));
        assertEquals(3L,targetJdbc.queryForObject("SELECT from_epoch FROM serial_recovery_intent WHERE id='INTENT-M'",Long.class));
        assertEquals(now.toInstant(),targetJdbc.queryForObject("SELECT created_at FROM serial_recovery_audit WHERE id='AUDIT-M'",java.sql.Timestamp.class).toInstant());
        assertEquals("{\"originalCommand\":\"原收货\"}",targetJdbc.queryForObject("SELECT payload FROM runtime_message_inbox WHERE id='INBOX-M'",String.class));
        // 不可变审计同主键内容不同必须报错，不能INSERT IGNORE吞掉差异。
        targetJdbc.update("UPDATE serial_recovery_audit SET reason='不同依据' WHERE id='AUDIT-M'");
        assertEquals("MIGRATION_CONTENT_MISMATCH",assertThrows(InventoryException.class,() -> store.copyTable("serial_recovery_audit","ENT-M","WH-M",null)).code());
        assertEquals("不同依据",targetJdbc.queryForObject("SELECT reason FROM serial_recovery_audit WHERE id='AUDIT-M'",String.class));
        sourceJdbc.update("UPDATE count_plan SET status='APPROVED' WHERE id='PLAN-M'");
        sourceJdbc.update("INSERT INTO count_plan(id,enterprise_id,warehouse_id,status,reason_code,created_at,updated_at) VALUES('ZZ-COLLISION','ENT-M','WH-M','DRAFT','CYCLE',?,?)",now,now);
        targetJdbc.update("INSERT INTO count_plan(id,enterprise_id,warehouse_id,status,reason_code,created_at,updated_at) VALUES('ZZ-COLLISION','OTHER','OTHER','DRAFT','CYCLE',?,?)",now,now);
        assertEquals("MIGRATION_ID_CONFLICT",assertThrows(InventoryException.class,() -> store.copyTable("count_plan","ENT-M","WH-M",null)).code());
        assertEquals("OTHER",targetJdbc.queryForObject("SELECT warehouse_id FROM count_plan WHERE id='ZZ-COLLISION'",String.class));
        assertEquals("DRAFT",targetJdbc.queryForObject("SELECT status FROM count_plan WHERE id='PLAN-M'",String.class),"同批先前更新也必须回滚");
        targetJdbc.update("UPDATE warehouse_route SET state='ACTIVE' WHERE enterprise_id='ENT-M' AND warehouse_id='WH-M'");
        assertEquals("MIGRATION_TARGET_IN_USE",assertThrows(InventoryException.class,() -> store.prepareTarget("ENT-M","WH-M","TARGET","SOURCE",now)).code());
        assertEquals("MIGRATION_TARGET_IN_USE",assertThrows(InventoryException.class,() -> store.copyTable("serial_recovery_intent","ENT-M","WH-M",null)).code());
        var mismatched=new MysqlDataSource();
        mismatched.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(targetMysql.getJdbcUrl(),"+08:00"));
        mismatched.setUser(targetMysql.getUsername()); mismatched.setPassword(targetMysql.getPassword());
        assertEquals("MIGRATION_TIME_MISMATCH",assertThrows(InventoryException.class,() -> new com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore(sourceJdbc.getDataSource(),mismatched)).code());
    }

    @Test void migrationDoesNotOverrideCountFreezeOrOpenUnrelatedMaintenance() {
        Clock clock=Clock.systemUTC();
        try(var session=sourceSessions.openSession(false)) {
            var master=new MasterdataService(session,clock);
            master.createWarehouse("WH-F","ENT-F","F","冻结仓","UTC");
            master.createLocation("LOC-F","GATE-F","ENT-F","WH-F","F","A","STORAGE",new BigDecimal("100"),"EA");
            session.commit();
        }
        sourceJdbc.update("UPDATE location_gate SET state='FROZEN',reason_code='COUNT' WHERE id='GATE-F'");
        try(var session=sourceSessions.openSession(false)) {
            var migrate=new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock);
            migrate.prepare("ENT-F","WH-F","SRC-F","DST-F"); migrate.copyFull("ENT-F","WH-F"); session.commit();
        }
        try(var session=sourceSessions.openSession(false)) {
            var migrate=new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock);
            assertEquals("MIGRATION_GATE_BUSY",assertThrows(InventoryException.class,() -> migrate.quiesce("ENT-F","WH-F")).code());
            session.rollback();
        }
        assertEquals("FROZEN",sourceJdbc.queryForObject("SELECT state FROM location_gate WHERE id='GATE-F'",String.class));
        var store=new com.lrj.wms.inventory.migrate.infrastructure.WarehouseMigrationStore(sourceJdbc.getDataSource(),targetJdbc.getDataSource());
        assertEquals("MIGRATION_GATE_BUSY",assertThrows(InventoryException.class,() -> store.activateTarget("ENT-F","WH-F",2,"DST-F","SRC-F",java.sql.Timestamp.from(clock.instant()))).code());
        assertEquals("FROZEN",targetJdbc.queryForObject("SELECT state FROM location_gate WHERE id='GATE-F'",String.class));
        assertEquals("COPYING",targetJdbc.queryForObject("SELECT state FROM warehouse_route WHERE warehouse_id='WH-F'",String.class));
    }

    @Test void cutoverRequiresCopyValidationBeforeTargetActivation() {
        Clock clock=Clock.systemUTC();
        try(var session=sourceSessions.openSession(false)) {
            new MasterdataService(session,clock).createWarehouse("WH-P","ENT-P","P","未复制仓","UTC");
            var migrate=new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock);
            migrate.prepare("ENT-P","WH-P","SRC-P","DST-P"); migrate.quiesce("ENT-P","WH-P"); session.commit();
        }
        try(var session=sourceSessions.openSession(false)) {
            var migrate=new WarehouseMigrationService(session,sourceJdbc,targetJdbc,clock);
            assertEquals("MIGRATION_MISMATCH",assertThrows(InventoryException.class,() -> migrate.switchEpoch("ENT-P","WH-P")).code());
            session.rollback();
        }
        assertEquals("COPYING",targetJdbc.queryForObject("SELECT state FROM warehouse_route WHERE warehouse_id='WH-P'",String.class));
        assertEquals("QUIESCING",sourceJdbc.queryForObject("SELECT state FROM warehouse_route WHERE warehouse_id='WH-P'",String.class));
    }

    private static final String ACTIVE_COPY = WarehouseMigrationService.ACTIVE;

    private static StockBucketKey bucket() {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-M", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static StockBucketKey bucketB() {
        return StockBucketKey.of("ENT-1", "WH-B", "OWNER-1", "LOC-B", "SKU-M", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static MySQLContainer mysql(String database) {
        return new MySQLContainer("mysql:8.4.11").withDatabaseName(database)
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
    }

    private static MysqlDataSource datasource(MySQLContainer mysql) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }

    private static SqlSessionFactory sessions(String name, MysqlDataSource source) {
        Configuration config = new Configuration(new Environment(name, new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(WarehouseRouteMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }
}
