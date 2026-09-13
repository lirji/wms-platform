package com.lrj.wms.inventory.serial;

import com.lrj.wms.contract.messaging.*;
import com.lrj.wms.fulfillment.*;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.inventory.infrastructure.*;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.infrastructure.*;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 两个真实业务库验证原命令、部分收货和最终事务；登记端口为明确夹具，真实HTTP另有进程验收。 */
class SerialTransferCommandIT {
    @Test void originalMembersBoundQuantityAndFinalReceiptFailureRollsBack() {
        try(var inventory=new MySQLContainer("mysql:8.4.11").withDatabaseName("inventory");var fulfillment=new MySQLContainer("mysql:8.4.11").withDatabaseName("fulfillment")) {
            inventory.start();fulfillment.start();Clock clock=Clock.fixed(Instant.now(),ZoneOffset.UTC);
            var stock=factory(inventory,"classpath:db/migration",MasterdataMapper.class,MasterdataHttpMapper.class,InventoryMapper.class,OutboxMapper.class,CommandDedupMapper.class,
                    LocalSerialMapper.class,SerialReleaseMapper.class,SerialRecoveryMapper.class,SerialTransferCommandMapper.class);
            var orders=factory(fulfillment,"filesystem:../wms-fulfillment/src/main/resources/db/migration/fulfillment",TransferMapper.class,SerialTransferMapper.class,FulfillmentMapper.class);
            var stockSql=new JdbcTemplate(source(inventory));var orderSql=new JdbcTemplate(source(fulfillment));
            var claims=new SerialReceiptIT.MemoryRegistry();
            try(var session=stock.openSession(false)) {
                var master=new MasterdataService(session,clock);
                master.createSku(com.lrj.wms.inventory.masterdata.domain.SkuPolicy.create("SKU","E","SKU","序列商品","EA",0,false,true,false,1,"ACTIVE"),"UNIT");
                for(String w:List.of("A","B")) {
                    master.createWarehouse(w,"E",w,w,"UTC");master.createLocation("LOC-"+w,"GATE-"+w,"E",w,"LOC-"+w,"ZONE","STORAGE",BigDecimal.valueOf(100),"EA");
                }
                for(String sn:List.of("SN-1","SN-2")) new SerialReceiptService(session,clock,claims).receiveHold("E","A","INITIAL-"+sn,"INITIAL","actor",sn,bucket("A"));
                session.commit();
            }
            Map<String,Object> issued;
            try(var session=orders.openSession(false)) {
                new TransferService(session,clock).create("E","TR","A","B",List.of(Map.of("lineId","LINE","skuId","SKU","plannedQty",BigDecimal.valueOf(2))));
                issued=new SerialTransferService(session,clock).request("E","TR","LINE","ISSUE","ISSUE",context("A"),selection("SN-1","SN-2"),BigDecimal.valueOf(2),null,null,"actor");session.commit();
            }
            String issueId=(String)issued.get("commandId");var command=command(issued);
            try(var session=orders.openSession(false)) {
                assertEquals(issueId,new SerialTransferService(session,clock).request("E","TR","LINE","ISSUE","ISSUE",context("A"),selection("SN-2","SN-1"),BigDecimal.valueOf(2),null,null,"actor").get("commandId"));
                assertThrows(TransferException.class,()->new TransferService(session,clock).issue("E","TR","LINE","BYPASS",BigDecimal.ONE));session.rollback();
            }
            try(var session=stock.openSession(false)) {new SerialTransferCommandService(session,clock).accept(message(command));session.commit();}
            assertEquals(0,SerialTransferCommandService.completeDue(stock,clock,"E","A"));assertQuantity(stockSql,"A",0);
            assertEquals(0,orderSql.queryForObject("SELECT issued_qty FROM transfer_line WHERE id='LINE'",BigDecimal.class).signum());
            SerialReleaseRegistryPort released=new SerialReleaseRegistryPort() {
                public Map<String,Object> prepare(String e,String sku,String sn,String transfer,String w,String target,String op,long epoch) {return Map.of();}
                public Map<String,Object> release(String e,String sku,String sn,String transfer,String w,String ref,long epoch) {return Map.of("sourceRelease",Map.of("enterpriseId",e,"sourceWarehouseId",w,"skuId",sku,"normalizedSerial",sn,"transferId",transfer,"sourceReleaseRef",ref,"fromEpoch",epoch));}
            };
            assertEquals(2,new SerialReleaseRecoveryService(stock,clock,released).execute("E","A").completed());
            Clock later=Clock.offset(clock,Duration.ofSeconds(3));assertEquals(1,SerialTransferCommandService.completeDue(stock,later,"E","A"));
            var result=result(stockSql,command);
            orderSql.execute("ALTER TABLE transfer_serial_command ADD CONSTRAINT test_final_failure CHECK(state<>'COMPLETE')");
            try(var session=orders.openSession(false)) {assertThrows(RuntimeException.class,()->new SerialTransferService(session,clock).complete(result));session.rollback();}
            assertEquals(0,orderSql.queryForObject("SELECT issued_qty FROM transfer_line WHERE id='LINE'",BigDecimal.class).signum());
            assertEquals(0,orderSql.queryForObject("SELECT COUNT(*) FROM transfer_fact",Integer.class));
            orderSql.execute("ALTER TABLE transfer_serial_command DROP CHECK test_final_failure");
            try(var session=orders.openSession(false)) {var service=new SerialTransferService(session,clock);service.complete(result);service.complete(result);session.commit();}
            assertEquals(0,orderSql.queryForObject("SELECT issued_qty FROM transfer_line WHERE id='LINE'",BigDecimal.class).compareTo(BigDecimal.valueOf(2)));
            Map<String,Object> receipt;
            try(var session=orders.openSession(false)) {
                var transfer=new TransferService(session,clock);var auth=transfer.authorizeReceipt("E","TR","LINE","AUTH",BigDecimal.ONE);
                receipt=new SerialTransferService(session,clock).request("E","TR","LINE","RECEIVE","RECEIVE",context("B"),selection("SN-1"),BigDecimal.ONE,(String)auth.get("authorizationId"),((Number)auth.get("tokenVersion")).longValue(),"actor");session.commit();
            }
            try(var session=orders.openSession(false)) {
                var transfer=new TransferService(session,clock);var auth=transfer.authorizeReceipt("E","TR","LINE","AUTH-2",BigDecimal.ONE);
                assertThrows(TransferException.class,()->new SerialTransferService(session,clock).request("E","TR","LINE","DUP-RECEIVE","RECEIVE",context("B"),selection("SN-1"),BigDecimal.ONE,(String)auth.get("authorizationId"),((Number)auth.get("tokenVersion")).longValue(),"actor"));session.rollback();
            }
            var targetCommand=command(receipt);
            try(var session=stock.openSession(false)) {new SerialTransferCommandService(session,clock).accept(message(targetCommand));new SerialTransferCommandService(session,clock).accept(message(targetCommand));session.commit();}
            assertQuantity(stockSql,"B",1);assertEquals(0,SerialTransferCommandService.completeDue(stock,clock,"E","B"));
            SerialTransferRegistryPort destination=new SerialTransferRegistryPort() {
                public Map<String,Object> startReceiving(String e,String sku,String sn,String transfer,String w,String ref,long epoch) {return Map.of();}
                public Map<String,Object> confirmDestination(String e,String sku,String sn,String transfer,String w,String ref) {return Map.of("state","ACTIVE","ownerWarehouseId",w,"receiptOperationId",ref,"normalizedSerial",sn,"ownerEpoch",2L);}
            };
            assertEquals(1,new SerialRecoveryService(stock,clock,claims,destination).execute("E","B").completed());
            assertEquals(1,SerialTransferCommandService.completeDue(stock,later,"E","B"));
            try(var session=orders.openSession(false)) {var service=new SerialTransferService(session,clock);service.complete(result(stockSql,targetCommand));service.complete(result(stockSql,targetCommand));session.commit();}
            assertEquals(0,orderSql.queryForObject("SELECT received_qty FROM transfer_line WHERE id='LINE'",BigDecimal.class).compareTo(BigDecimal.ONE));
            assertEquals(2L,stockSql.queryForObject("SELECT owner_epoch FROM local_serial WHERE warehouse_id='B' AND serial_id='SN-1'",Long.class));
            assertEquals(2,stockSql.queryForObject("SELECT COUNT(*) FROM local_serial WHERE warehouse_id='A' AND state='SEALED'",Integer.class));
        }
    }
    private static SerialTransferCommand command(Map<String,Object> view) {return RuntimeMessage.JSON.treeToValue((tools.jackson.databind.JsonNode)view.get("command"),SerialTransferCommand.class);}
    private static RuntimeMessage message(SerialTransferCommand command) {return new RuntimeMessage(1,command.commandId(),"wms-fulfillment","E",command.warehouseId(),SerialTransferCommand.EVENT,command.commandId(),1,Instant.now().toString(),null,RuntimeMessage.JSON.valueToTree(command));}
    private static RuntimeMessage result(JdbcTemplate sql,SerialTransferCommand command) {
        String payload=sql.queryForObject("SELECT CAST(payload AS CHAR) FROM outbox_event WHERE aggregate_id=? AND event_type=?",String.class,command.commandId(),SerialTransferCommand.RESULT);
        return new RuntimeMessage(1,"RESULT-"+command.action(),"wms-inventory","E",command.warehouseId(),SerialTransferCommand.RESULT,command.commandId(),1,Instant.now().toString(),null,RuntimeMessage.JSON.readTree(payload));
    }
    private static SerialExecutionSelection selection(String...sn) {return new SerialExecutionSelection(1,Arrays.stream(sn).map(s->new SerialExecutionSelection.Identity(s,1L)).toList());}
    private static StockPostingContext context(String w) {return new StockPostingContext("TR","OWNER","SKU","EA","LOC-"+w,null,"NO_LOT","HOLD",null,null);}
    private static StockBucketKey bucket(String w) {return StockBucketKey.of("E",w,"OWNER","LOC-"+w,"SKU","NO_LOT","HOLD");}
    private static void assertQuantity(JdbcTemplate sql,String w,int quantity) {assertEquals(0,sql.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE warehouse_id=?",BigDecimal.class,w).compareTo(BigDecimal.valueOf(quantity)));}
    private static com.mysql.cj.jdbc.MysqlDataSource source(MySQLContainer db) {var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(db.getJdbcUrl(),"UTC"));source.setUser(db.getUsername());source.setPassword(db.getPassword());return source;}
    private static SqlSessionFactory factory(MySQLContainer db,String location,Class<?>...mappers) {var source=source(db);Flyway.configure().dataSource(source).locations(location).load().migrate();var config=new Configuration(new Environment(db.getDatabaseName(),new JdbcTransactionFactory(),source));com.lrj.wms.runtime.db.DatabaseInstants.configure(config);for(var mapper:mappers) config.addMapper(mapper);return new SqlSessionFactoryBuilder().build(config);}
}
