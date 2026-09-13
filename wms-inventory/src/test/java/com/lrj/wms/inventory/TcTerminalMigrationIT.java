package com.lrj.wms.inventory;

import com.lrj.wms.contract.messaging.TcTerminalNotice;
import com.lrj.wms.contract.tcc.WarehouseTryRequest;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.migrate.WarehouseMigrationService;
import com.lrj.wms.inventory.tcc.*;
import com.lrj.wms.runtime.db.DatabaseBudget;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seata.rm.tcc.api.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 两个真实数据库验证原Fence迁移；TC通知为明确夹具，真实TC/Kafka链路另由进程用例证明。 */
class TcTerminalMigrationIT {
    @Test void terminalProofAndOriginalFenceMoveWithoutReexecutingBusiness() throws Exception {
        try(var a=new MySQLContainer("mysql:8.4.11").withDatabaseName("source").withPassword(UUID.randomUUID().toString());
            var b=new MySQLContainer("mysql:8.4.11").withDatabaseName("target").withPassword(UUID.randomUUID().toString())) {
            a.start();b.start();var source=RuntimeRmProcessesIT.source(a);var target=RuntimeRmProcessesIT.source(b);
            new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", "").initialize(source,()->Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate());
            new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", "").initialize(target,()->Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate());RuntimeRmProcessesIT.seed(a,"A");
            var budget=new DatabaseBudget(4,0,500,250,1,500,1500);Clock clock=Clock.systemUTC();
            var sourceSessions=InventoryPersistence.sessions(source,new JdbcTransactionFactory(),budget);
            var template=new SqlSessionTemplate(InventoryPersistence.sessions(source,new SpringManagedTransactionFactory(),budget));
            var manager=new DataSourceTransactionManager(source);var tx=new TransactionTemplate(manager);
            String action=TcTerminalService.resource("cluster","A"),xid="original-xid";
            var rm=new RuntimeTccCoordinator(template,tx,InventoryTccFence.bind(source,manager),clock,"A",action);
            var data=new AtomicReference<String>();
            var request=new WarehouseTryRequest(1,"ENT","A","OWNER","ATT","ATT","A",1,List.of(
                    new WarehouseTryRequest.Line("L1","SKU","LOC","NO_LOT",BigDecimal.ONE,"EA",0)));
            rm.tryReserve(request,xid,(ignored,body)->{data.set(body);return 101;});
            try(var session=sourceSessions.openSession(false)) {
                var migration=new WarehouseMigrationService(session,new JdbcTemplate(source),new JdbcTemplate(target),clock);migration.prepare("ENT","A","A","B");session.commit();
            }
            try(var session=sourceSessions.openSession(false)) {
                assertEquals("MIGRATION_TCC_PROOF_REQUIRED",assertThrows(InventoryException.class,()->new WarehouseMigrationService(session,new JdbcTemplate(source),new JdbcTemplate(target),clock).quiesce("ENT","A")).code());
                assertThrows(InventoryException.class,()->TcTerminalService.accept(session,notice(xid,"cluster",9),"cluster","group"));
            }
            var context=BusinessActionContextUtil.getBusinessActionContext(xid,101,action,data.get());
            var commit=RuntimeTccCoordinator.class.getMethod("confirm",BusinessActionContext.class);
            assertTrue(rm.commitFence(commit,rm,xid,101L,new Object[]{context}));
            var sql=new JdbcTemplate(source);
            try(var session=sourceSessions.openSession(false)) {
                assertThrows(MessageRejectedException.class,()->TcTerminalService.accept(session,notice(xid,"wrong",9),"cluster","group"));
                assertEquals("MIGRATION_TCC_PROOF_REQUIRED",assertThrows(InventoryException.class,()->new WarehouseMigrationService(session,new JdbcTemplate(source),new JdbcTemplate(target),clock).quiesce("ENT","A")).code());
            }
            sql.execute("ALTER TABLE inventory_tcc_terminal ADD CONSTRAINT ck_terminal_test CHECK(terminal_status<>9)");
            try(var session=sourceSessions.openSession(false)) {assertThrows(RuntimeException.class,()->TcTerminalService.accept(session,notice(xid,"cluster",9),"cluster","group"));}
            assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM inventory_tcc_terminal",Integer.class));
            sql.execute("ALTER TABLE inventory_tcc_terminal DROP CHECK ck_terminal_test");
            try(var session=sourceSessions.openSession(false)) {
                TcTerminalService.accept(session,notice(xid,"cluster",9),"cluster","group");
                TcTerminalService.accept(session,notice(xid,"cluster",9),"cluster","group");session.commit();
            }
            // 原TC证明存在仍不能取消UNKNOWN实物；该事务夹具回滚，不伪造设备结案。
            try(var session=sourceSessions.openSession(false)) {
                var commands=new com.lrj.wms.inventory.inventory.StockCommandService(session,clock);
                commands.startPermit("ENT","A","wms-outbound","UNKNOWN-CMD","TASK",1,"ORIGINAL-OUT","PART","ORIGINAL-LINE",BigDecimal.ONE);
                commands.markUnknown("ENT","A","wms-outbound","UNKNOWN-CMD");
                assertEquals("PHYSICAL_RESULT_UNKNOWN",assertThrows(InventoryException.class,()->com.lrj.wms.inventory.inventory.OutboundCancellationGuard.stop(
                        session,"ENT","A","ORIGINAL-OUT","ATT","ATT","CANCEL","ORIGINAL-LINE")).code());session.rollback();
            }
            try(var session=sourceSessions.openSession(false)) {
                com.lrj.wms.inventory.inventory.OutboundCancellationGuard.stop(session,"ENT","A","ORIGINAL-OUT","ATT","ATT","CANCEL","ORIGINAL-LINE");session.commit();
            }
            try(var session=sourceSessions.openSession(false)) {
                var migration=new WarehouseMigrationService(session,new JdbcTemplate(source),new JdbcTemplate(target),clock);migration.copyFull("ENT","A");migration.quiesce("ENT","A");session.commit();
            }
            var targetSql=new JdbcTemplate(target);var original=sql.queryForMap("SELECT * FROM tcc_fence_log WHERE xid=?",xid);
            assertEquals(original,targetSql.queryForMap("SELECT * FROM tcc_fence_log WHERE xid=?",xid));
            targetSql.update("UPDATE tcc_fence_log SET status=1 WHERE xid=?",xid);
            try(var session=sourceSessions.openSession(false)) {
                assertEquals("MIGRATION_FENCE_MISMATCH",assertThrows(InventoryException.class,()->new WarehouseMigrationService(session,new JdbcTemplate(source),new JdbcTemplate(target),clock).switchEpoch("ENT","A")).code());
            }
            targetSql.update("UPDATE tcc_fence_log SET status=2 WHERE xid=?",xid);
            try(var session=sourceSessions.openSession(false)) {new WarehouseMigrationService(session,new JdbcTemplate(source),new JdbcTemplate(target),clock).switchEpoch("ENT","A");session.commit();}
            var targetTemplate=new SqlSessionTemplate(InventoryPersistence.sessions(target,new SpringManagedTransactionFactory(),budget));
            var targetManager=new DataSourceTransactionManager(target);
            var restored=new RuntimeTccCoordinator(targetTemplate,new TransactionTemplate(targetManager),InventoryTccFence.bind(target,targetManager),clock,"B",TcTerminalService.resource("cluster","B"));
            assertEquals(List.of(action),restored.historicalResources());
            for(int i=0;i<2;i++) assertTrue(restored.commitFence(commit,restored,xid,101L,new Object[]{context}));
            var cancel=RuntimeTccCoordinator.class.getMethod("cancel",BusinessActionContext.class);
            assertEquals("TCC_TERMINAL_CONFLICT",assertThrows(InventoryException.class,()->restored.rollbackFence(cancel,restored,xid,101L,new Object[]{context},action)).code());
            assertEquals(original,targetSql.queryForMap("SELECT * FROM tcc_fence_log WHERE xid=?",xid));
            assertEquals(1,targetSql.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type='ReservationConfirmed'",Integer.class));
            assertEquals(BigDecimal.ONE.intValue(),targetSql.queryForObject("SELECT reserved_qty FROM stock_balance",BigDecimal.class).intValueExact());
            assertEquals("A",targetSql.queryForObject("SELECT cell_id FROM inventory_tcc_intent",String.class));
            try(var session=InventoryPersistence.sessions(target,new JdbcTransactionFactory(),budget).openSession(false)) {
                assertEquals("CANCELLATION_IN_PROGRESS",assertThrows(InventoryException.class,()->new com.lrj.wms.inventory.inventory.StockCommandService(session,clock)
                    .startPermit("ENT","A","wms-outbound","LATE-CMD","LATE-TASK",1,"ORIGINAL-OUT","LATE-PART","ORIGINAL-LINE",BigDecimal.ONE)).code());session.rollback();
            }
            assertEquals(sql.queryForList("SELECT * FROM outbound_cancellation_guard"),targetSql.queryForList("SELECT * FROM outbound_cancellation_guard"));
            assertEquals(1,targetSql.queryForObject("SELECT route_epoch FROM inventory_tcc_intent",Integer.class));
            // 历史预占没有原RM意图时不能因意图表没查到它而放过；不猜测补造TC来源。
            targetSql.update("INSERT INTO reservation(id,enterprise_id,warehouse_id,allocation_id,attempt_id,request_digest,digest_version,state,xid,branch_id,action_name,route_epoch,version,created_at,updated_at) SELECT 'UNOWNED',enterprise_id,warehouse_id,'UNOWNED','UNOWNED',request_digest,digest_version,state,'unowned-xid',999,action_name,route_epoch,0,created_at,updated_at FROM reservation LIMIT 1");
            assertEquals(1,targetTemplate.getMapper(com.lrj.wms.inventory.migrate.WarehouseRouteMapper.class).runtimeTccIntents("ENT","A"));
        }
    }
    private static RuntimeMessage notice(String xid,String cluster,int status) {
        return new RuntimeMessage(1,"notice","wms-fulfillment","ENT","A",TcTerminalNotice.EVENT,"ATT",1,Instant.now().toString(),null,
                RuntimeMessage.JSON.valueToTree(new TcTerminalNotice(1,"ATT","ATT",xid,cluster,"wms-fulfillment","group",status)));
    }
}
