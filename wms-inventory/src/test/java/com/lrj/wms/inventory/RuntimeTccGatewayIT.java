package com.lrj.wms.inventory;

import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.inventory.tcc.*;
import com.lrj.wms.inventory.inventory.*;
import com.lrj.wms.inventory.inventory.domain.*;
import com.lrj.wms.inventory.masterdata.*;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.migrate.WarehouseRouteMapper;
import com.lrj.wms.runtime.db.DatabaseBudget;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.apache.seata.rm.tcc.api.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证登记/库存/Fence的崩溃窗口；TC网络二阶段另由原生进程用例覆盖。 */
class RuntimeTccGatewayIT {
    private static MySQLContainer mysql;
    private static JdbcTemplate db;
    private static RuntimeTccCoordinator rm;
    private static SqlSessionTemplate sessions;
    private static TransactionTemplate tx;
    @BeforeAll static void start(){
        mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("inventory").withPassword(UUID.randomUUID().toString());mysql.start();
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setURL(mysql.getJdbcUrl());source.setUser(mysql.getUsername());source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();db=new JdbcTemplate(source);
        sessions=new SqlSessionTemplate(InventoryPersistence.sessions(source,new SpringManagedTransactionFactory(),new DatabaseBudget(4,0,500,250,1,500,1500)));
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        rm=new RuntimeTccCoordinator(sessions,tx,InventoryTccFence.bind(source,manager),Clock.systemUTC(),"CELL","ACTION");
        tx.executeWithoutResult(status->{
            var master=new MasterdataService(sessions,Clock.systemUTC());
            master.createWarehouse("WH","ENT","WH","测试仓","UTC");
            master.createLocation("LOC","GATE","ENT","WH","LOC","A","STORAGE",new BigDecimal("1000"),"EA");
            master.createSku(SkuPolicy.create("SKU","ENT","SKU","测试SKU","EA",0,false,false,false,1,"ACTIVE"),"UNIT");
            new InventoryApplicationService(sessions,Clock.systemUTC()).receive("ENT","WH","RECEIVE","DOC","ACTOR",
                    StockBucketKey.of("ENT","WH","OWNER","LOC","SKU","NO_LOT","GOOD"),Quantity.parse("100",0));
            sessions.getMapper(WarehouseRouteMapper.class).insertIgnore("ROUTE","ENT","WH","CELL",null,1,"ACTIVE",null,java.sql.Timestamp.from(Instant.now()));
        });
    }
    @AfterAll static void stop(){if(mysql!=null)mysql.stop();}

    @Test void knownBranchRetriesFenceTransactionWithoutRegisteringAgain() throws Exception {
        var request=request("known",2);var calls=new AtomicInteger();var data=new AtomicReference<String>();
        RuntimeTccCoordinator.Registration register=(xid,body)->{calls.incrementAndGet();data.set(body);return 101;};
        db.execute("ALTER TABLE inventory_tcc_intent ADD CONSTRAINT ck_test_try_final CHECK(state<>'TRIED')");
        RuntimeException failed=assertThrows(RuntimeException.class,()->rm.tryReserve(request,"xid-known",register));
        assertTrue(stack(failed).contains("ck_test_try_final"));
        assertEquals(0,count("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-known'"));
        assertEquals(0,count("SELECT COUNT(*) FROM reservation"));
        assertEquals("REGISTERED",state("known"));
        db.execute("ALTER TABLE inventory_tcc_intent DROP CHECK ck_test_try_final");
        var tried=rm.tryReserve(request,"xid-known",register);
        assertEquals(tried,rm.tryReserve(request,"xid-known",register));assertEquals(1,calls.get());
        assertEquals(1,count("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-known'"));
        assertEquals(2,db.queryForObject("SELECT reserved_qty FROM stock_balance",BigDecimal.class).intValueExact());
        assertEquals("TCC_OWNER_CONFLICT",assertThrows(InventoryException.class,()->rm.tryReserve(request("known",3),"xid-known",register)).code());
        var context=BusinessActionContextUtil.getBusinessActionContext("xid-known",101,"ACTION",data.get());
        var method=RuntimeTccCoordinator.class.getMethod("confirm",BusinessActionContext.class);
        db.execute("ALTER TABLE outbox_event ADD CONSTRAINT ck_test_confirm_final CHECK(event_type<>'ReservationConfirmed')");
        assertThrows(RuntimeException.class,()->rm.commitFence(method,rm,"xid-known",101L,new Object[]{context}));
        assertEquals("TRIED",state("known"));assertEquals("TRIED",db.queryForObject("SELECT state FROM reservation",String.class));
        db.execute("ALTER TABLE outbox_event DROP CHECK ck_test_confirm_final");
        assertTrue(rm.commitFence(method,rm,"xid-known",101L,new Object[]{context}));
        assertTrue(rm.commitFence(method,rm,"xid-known",101L,new Object[]{context}));
        assertEquals("CONFIRMED",state("known"));
        assertEquals(1,count("SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'"));
        assertNull(BusinessActionContextUtil.getContext());
    }
    @Test void unknownRegistrationDoesNotRepeatAndEmptyRollbackChecksRouteBeforeFence() throws Exception {
        var request=request("unknown",1);var calls=new AtomicInteger();var data=new AtomicReference<String>();
        RuntimeTccCoordinator.Registration register=(xid,body)->{calls.incrementAndGet();data.set(body);throw new InventoryException("TCC_REGISTRATION_UNKNOWN","丢失登记响应");};
        assertThrows(InventoryException.class,()->rm.tryReserve(request,"xid-unknown",register));
        assertEquals("TCC_REGISTRATION_UNKNOWN",assertThrows(InventoryException.class,()->rm.tryReserve(request,"xid-unknown",register)).code());
        assertEquals(1,calls.get());assertEquals("REGISTERING",state("unknown"));
        var context=BusinessActionContextUtil.getBusinessActionContext("xid-unknown",102,"ACTION",data.get());
        var method=RuntimeTccCoordinator.class.getMethod("cancel",BusinessActionContext.class);
        db.update("UPDATE warehouse_route SET route_epoch=2 WHERE id='ROUTE'");
        assertEquals("STALE_ROUTE",assertThrows(InventoryException.class,()->rm.rollbackFence(method,rm,"xid-unknown",102L,new Object[]{context},"ACTION")).code());
        assertEquals(0,count("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-unknown'"));
        assertEquals("REGISTERING",state("unknown"));
        db.update("UPDATE warehouse_route SET route_epoch=1 WHERE id='ROUTE'");
        assertTrue(rm.rollbackFence(method,rm,"xid-unknown",102L,new Object[]{context},"ACTION"));
        assertEquals("CANCELLED",state("unknown"));
        assertEquals(1,count("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='xid-unknown'"));
        assertEquals("TCC_ALREADY_CANCELLED",assertThrows(InventoryException.class,()->rm.tryReserve(request,"xid-unknown",register)).code());
        assertEquals(1,calls.get());
    }
    private static WarehouseTryRequest request(String id,int qty){return new WarehouseTryRequest(1,"ENT","WH","OWNER","ALLOC-"+id,"ATT-"+id,"CELL",1,
            List.of(new WarehouseTryRequest.Line("LINE","SKU","LOC","NO_LOT",BigDecimal.valueOf(qty),"EA",0)));}
    private static int count(String sql){return db.queryForObject(sql,Integer.class);}
    private static String state(String id){return db.queryForObject("SELECT state FROM inventory_tcc_intent WHERE attempt_id=?",String.class,"ATT-"+id);}
    private static String stack(Throwable error){var s=new StringBuilder();for(;error!=null;error=error.getCause())s.append(error.getMessage());return s.toString();}
}
