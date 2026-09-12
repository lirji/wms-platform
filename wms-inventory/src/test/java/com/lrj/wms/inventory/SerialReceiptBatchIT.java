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
}
