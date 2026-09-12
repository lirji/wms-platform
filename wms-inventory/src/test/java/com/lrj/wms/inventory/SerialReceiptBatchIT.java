package com.lrj.wms.inventory;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.messaging.StockCommandMessageHandler;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证数量、完整批次、逐身份意图和回执原子性；不以Mock证明事务。 */
class SerialReceiptBatchIT {
    static MySQLContainer mysql;
    static SqlSessionFactory sessions;
    static JdbcTemplate jdbc;
    @BeforeAll static void prepare() {
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory");mysql.start();
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
        source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        sessions=InventoryPersistence.sessions(source,new JdbcTransactionFactory(),new DatabaseBudget(4,0,1000,500,5,3000,10000));
        jdbc=new JdbcTemplate(source);
    }
    @AfterAll static void cleanup() {mysql.stop();}
    private static void seed(String e) {
        try(var session=sessions.openSession(false)) {
            var masters=new MasterdataService(session,Clock.systemUTC());
            masters.createWarehouse("WH-"+e,e,"WH","测试仓","UTC");
            masters.createLocation("LOC-"+e,"GATE-"+e,e,"WH-"+e,"LOC","A","RECEIVING",new BigDecimal("100"),"EA");
            masters.createSku(SkuPolicy.create("SKU-"+e,e,"SKU","序列商品","EA",0,false,true,false,1,"ACTIVE"),"UNIT-"+e);
            session.commit();
        }
    }
    private static RuntimeMessage message(String e,String cmd,String... serials) {
        var body=RuntimeMessage.JSON.createObjectNode();
        body.put("action","RECEIVE");body.put("commandId",cmd);body.put("qty",serials.length);
        body.put("factParentId","ORDER");body.put("factPartId",cmd);body.put("factLineId","LINE");
        body.put("actorId","operator");body.put("sourceExecutionId",cmd);
        body.set("postingContext",RuntimeMessage.JSON.valueToTree(new StockPostingContext("ORDER","OWNER","SKU-"+e,"EA","LOC-"+e,null,"NO_LOT","HOLD",null,null)));
        body.set("serialObservation",RuntimeMessage.JSON.valueToTree(new SerialReceiptObservation(1,List.of(serials))));
        return new RuntimeMessage(1,UUID.randomUUID().toString(),"wms-inbound",e,"WH-"+e,"StockCommandRequested",cmd,1,Instant.now().toString(),"test",body);
    }
    private static void apply(RuntimeMessage message) {
        try(var session=sessions.openSession(false)) {new StockCommandMessageHandler(Clock.systemUTC()).apply(session,message);session.commit();}
    }
    private static int count(String table,String e) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE enterprise_id=?",Integer.class,e);}

    @Test void wholeReceiptReplaysWithoutRecountAndCannotReplaceIdentities() {
        String e="BATCH";seed(e);apply(message(e,"RECEIVE","sn-b"," SN-A "));
        apply(message(e,"RECEIVE","SN-A","SN-B"));
        assertEquals(1,count("stock_posting",e));assertEquals(1,count("stock_ledger",e));
        assertEquals(2,count("local_serial",e));assertEquals(2,count("serial_recovery_intent",e));
        assertEquals(0,jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE enterprise_id=?",BigDecimal.class,e).compareTo(new BigDecimal("2")));
        assertEquals(List.of("HOLD_RECEIVED","HOLD_RECEIVED"),jdbc.queryForList("SELECT state FROM local_serial WHERE enterprise_id=? ORDER BY serial_id",String.class,e));
        assertEquals("SERIAL_BATCH_CONFLICT",assertThrows(InventoryException.class,()->apply(message(e,"RECEIVE","SN-A","SN-C"))).code());
        // 另一个批次不能借已存在的身份增加数量；整批包括新身份也必须回滚。
        assertEquals("SERIAL_ALREADY_RECEIVED",assertThrows(InventoryException.class,()->apply(message(e,"SECOND","SN-0","SN-A"))).code());
        assertEquals(1,count("serial_receipt_batch",e));assertEquals(2,count("local_serial",e));assertEquals(1,count("stock_command",e));
    }

    @Test void finalBatchWriteFailureRollsBackQuantityIdentitiesAndOutbox() {
        String e="ROLLBACK";seed(e);
        jdbc.execute("ALTER TABLE serial_receipt_batch ADD CONSTRAINT test_batch_final CHECK(enterprise_id<>'ROLLBACK' OR state<>'APPLIED')");
        try {
            assertThrows(RuntimeException.class,()->apply(message(e,"RECEIVE","SN-1","SN-2")));
            for(String table:List.of("stock_balance","stock_command","stock_posting","stock_ledger","local_serial","serial_recovery_intent","serial_receipt_batch","outbox_event"))
                assertEquals(0,count(table,e),table);
        } finally {jdbc.execute("ALTER TABLE serial_receipt_batch DROP CHECK test_batch_final");}
        apply(message(e,"RECEIVE","SN-1","SN-2"));assertEquals(2,count("local_serial",e));assertEquals(1,count("stock_ledger",e));
    }

    @Test void missingOrMalformedObservationCannotPostSerialStock() {
        String e="INVALID";seed(e);var message=message(e,"RECEIVE","SN-1");
        var body=(tools.jackson.databind.node.ObjectNode)message.payload();
        body.put("qty",2);
        assertEquals("SERIAL_OBSERVATION_REQUIRED",assertThrows(MessageRejectedException.class,()->apply(message)).getMessage());
        body.put("qty",1);((tools.jackson.databind.node.ObjectNode)body.path("serialObservation")).put("schemaVersion",4294967297L);
        assertThrows(MessageRejectedException.class,()->apply(message));body.remove("serialObservation");
        assertThrows(MessageRejectedException.class,()->apply(message));assertEquals(0,count("stock_balance",e));
        assertThrows(IllegalArgumentException.class,()->new SerialReceiptObservation(1,List.of("sn-1","SN-1")));
    }

    @Test void existingPostingCannotAcquireAnInventedReceiptManifest() {
        String e="LEGACY";seed(e);
        try(var session=sessions.openSession(false)) {
            new com.lrj.wms.inventory.inventory.StockCommandService(session,Clock.systemUTC()).applyReceive(e,"WH-"+e,"wms-inbound","RECEIVE","ORDER","RECEIVE","LINE",
                    "ORDER","operator","RECEIVE",com.lrj.wms.inventory.inventory.domain.StockBucketKey.of(e,"WH-"+e,"OWNER","LOC-"+e,"SKU-"+e,"NO_LOT","HOLD"),
                    com.lrj.wms.inventory.inventory.domain.Quantity.parse("1",0));session.commit();
        }
        assertEquals("SERIAL_BATCH_CONTEXT_REQUIRED",assertThrows(InventoryException.class,()->apply(message(e,"RECEIVE","SN"))).code());
        assertEquals(0,count("serial_receipt_batch",e));assertEquals(0,count("local_serial",e));assertEquals(1,count("stock_ledger",e));
    }

    private static RuntimeMessage quality(String e,String command,long version,List<String> good,List<String> rejected) {
        var original=message(e,command,"SN-A","SN-B");var body=(tools.jackson.databind.node.ObjectNode)original.payload();
        body.remove("serialObservation");body.put("action","QUALITY");body.put("qty",good.size()+rejected.size());
        body.put("factParentId","RECEIVE");body.put("factPartId",Long.toString(version));
        body.set("qualityDecision",RuntimeMessage.JSON.valueToTree(new ReceiptQualityDecision("RECEIVE",command,version,
                BigDecimal.valueOf(good.size()),BigDecimal.valueOf(rejected.size()))));
        body.set("serialQualityObservation",RuntimeMessage.JSON.valueToTree(new SerialQualityObservation(1,good,rejected)));
        return original;
    }
    /** 明确授权夹具用于库存原子性测试；真实登记授权链另见SerialRegistryProcessesIT。 */
    private static void authorize(String e) {jdbc.update("UPDATE local_serial SET state='AUTHORIZED',registry_state='ACTIVE',owner_epoch=1 WHERE enterprise_id=?",e);}
    private static Map<String,String> qualities(String e) {
        var result=new TreeMap<String,String>();
        jdbc.query("SELECT s.serial_id,b.quality_code FROM local_serial s JOIN stock_balance b ON b.id=s.balance_id WHERE s.enterprise_id=?",rs->{
            result.put(rs.getString(1),rs.getString(2));},e);return result;
    }
    @Test void qualityRequiresAuthorizedExactBatchAndOldReplayKeepsNewerIdentityState() {
        String e="QUALITY";seed(e);apply(message(e,"RECEIVE","SN-A","SN-B"));
        var first=quality(e,"Q1",1,List.of("SN-A"),List.of("SN-B"));
        assertEquals("SERIAL_REGISTRY_PENDING",assertThrows(InventoryException.class,()->apply(first)).code());
        assertEquals(0,count("stock_receipt_quality",e));assertEquals(Map.of("SN-A","HOLD","SN-B","HOLD"),qualities(e));
        authorize(e);apply(first);assertEquals(Map.of("SN-A","GOOD","SN-B","REJECTED"),qualities(e));
        assertThrows(InventoryException.class,()->apply(quality(e,"Q1",1,List.of("SN-B"),List.of("SN-A"))));
        assertEquals("SERIAL_BATCH_CONFLICT",assertThrows(InventoryException.class,()->apply(quality(e,"Q2",2,List.of("OTHER"),List.of()))).code());
        apply(quality(e,"Q2",2,List.of("SN-A","SN-B"),List.of()));apply(first);
        assertEquals(Map.of("SN-A","GOOD","SN-B","GOOD"),qualities(e));
        assertEquals(2L,jdbc.queryForObject("SELECT source_version FROM stock_receipt_quality WHERE enterprise_id=?",Long.class,e));
        assertEquals(3,count("stock_posting",e));
    }

    @Test void equalQuantitySerialSwapCannotBypassReservationAndFinalIdentityFailureRollsBack() {
        String e="SWAP";seed(e);apply(message(e,"RECEIVE","SN-A","SN-B"));authorize(e);
        apply(quality(e,"Q1",1,List.of("SN-A"),List.of("SN-B")));
        String good=jdbc.queryForObject("SELECT id FROM stock_balance WHERE enterprise_id=? AND quality_code='GOOD'",String.class,e);
        jdbc.update("UPDATE stock_balance SET reserved_qty=1 WHERE id=?",good);
        var swap=quality(e,"Q2",2,List.of("SN-B"),List.of("SN-A"));
        assertEquals("SERIAL_QUALITY_RESERVED",assertThrows(InventoryException.class,()->apply(swap)).code());
        jdbc.update("UPDATE stock_balance SET reserved_qty=0 WHERE id=?",good);
        // 对最后一个身份写入注入真实CHECK失败，前一个身份和全部质量/命令写入也必须回滚。
        jdbc.execute("ALTER TABLE local_serial ADD CONSTRAINT test_serial_quality_final CHECK(enterprise_id<>'SWAP' OR serial_id<>'SN-B' OR balance_id<>'"+good+"')");
        try {
            assertThrows(RuntimeException.class,()->apply(swap));
            assertEquals(Map.of("SN-A","GOOD","SN-B","REJECTED"),qualities(e));assertEquals(2,count("stock_posting",e));
            assertEquals(1L,jdbc.queryForObject("SELECT source_version FROM stock_receipt_quality WHERE enterprise_id=?",Long.class,e));
        } finally {jdbc.execute("ALTER TABLE local_serial DROP CHECK test_serial_quality_final");}
        apply(swap);assertEquals(Map.of("SN-A","REJECTED","SN-B","GOOD"),qualities(e));
    }

    @Test void movedGoodIdentityCannotBeDowngradedByReplacingItWithAnotherSerial() {
        String e="MOVED";seed(e);apply(message(e,"RECEIVE","SN-A","SN-B"));authorize(e);
        apply(quality(e,"Q1",1,List.of("SN-A"),List.of()));
        try(var session=sessions.openSession(false)) {
            new MasterdataService(session,Clock.systemUTC()).createLocation("STORAGE-"+e,"GATE-S-"+e,e,"WH-"+e,"STORAGE","A","STORAGE",new BigDecimal("100"),"EA");
            var source=com.lrj.wms.inventory.inventory.domain.StockBucketKey.of(e,"WH-"+e,"OWNER","LOC-"+e,"SKU-"+e,"NO_LOT","GOOD");
            var target=com.lrj.wms.inventory.inventory.domain.StockBucketKey.of(e,"WH-"+e,"OWNER","STORAGE-"+e,"SKU-"+e,"NO_LOT","GOOD");
            new com.lrj.wms.inventory.inventory.InventoryApplicationService(session,Clock.systemUTC()).move(e,"WH-"+e,"MOVE","ORDER","actor",source,target,com.lrj.wms.inventory.inventory.domain.Quantity.parse("1",0),false);
            var balance=session.getMapper(com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper.class).lockBalanceByDimension(e,"WH-"+e,"OWNER","STORAGE-"+e,"SKU-"+e,"NO_LOT","GOOD");
            // 明确的已移动身份前置夹具，不冒充尚未接通的来源序列号PUTAWAY消息。
            session.getMapper(com.lrj.wms.inventory.serial.LocalSerialMapper.class).updateState(e,"WH-"+e,"SN-A",String.valueOf(balance.get("id")),"AUTHORIZED","ACTIVE",null,1,java.sql.Timestamp.from(Instant.now()));session.commit();
        }
        assertEquals("QUALITY_ALREADY_MOVED",assertThrows(InventoryException.class,()->apply(quality(e,"Q2",2,List.of("SN-B"),List.of()))).code());
        apply(quality(e,"Q2",2,List.of("SN-A","SN-B"),List.of()));
        assertEquals(Map.of("SN-A","GOOD","SN-B","GOOD"),qualities(e));
    }
}
