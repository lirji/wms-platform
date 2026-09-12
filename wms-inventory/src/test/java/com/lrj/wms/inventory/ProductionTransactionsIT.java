package com.lrj.wms.inventory;

import com.lrj.wms.inventory.count.CountService;
import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.ReservationLineInput;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.tcc.ReservationTccAction;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.seata.rm.fence.SpringFenceHandler;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 直接装配生产 Bean：同时证明手动会话回滚和 Fence/库存本地原子性，避免测试自建工厂掩盖故障。 */
class ProductionTransactionsIT {
    @Test void manualRollbackAndFenceRollbackUseTheProductionAssembly() {
        try (var mysql = new MySQLContainer("mysql:8.4.11")) {
            mysql.start();
            new ApplicationContextRunner()
                    .withUserConfiguration(InventoryPersistence.class, com.lrj.wms.runtime.RuntimeAutoConfiguration.class)
                    .withPropertyValues("wms.inventory.datasource.url=" + mysql.getJdbcUrl(),
                            "wms.inventory.datasource.username=" + mysql.getUsername(),
                            "wms.inventory.datasource.password=" + mysql.getPassword())
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        var sessions = context.getBean(SqlSessionFactory.class);
                        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                        var clock = Clock.systemUTC();
                        try (var session = sessions.openSession(false)) {
                            assertFalse(session.getConnection().getAutoCommit());
                            var masterdata = new MasterdataService(session, clock);
                            masterdata.createWarehouse("WH", "ENT", "WH", "事务测试仓", "UTC");
                            masterdata.createLocation("LOC", "GATE", "ENT", "WH", "LOC", "Z", "STORAGE", new BigDecimal("100"), "EA");
                            session.commit();
                        }
                        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM warehouse", Integer.class));
                        try (var session = sessions.openSession(false)) {
                            assertThrows(InventoryException.class, () -> new CountService(session, clock)
                                    .create("ENT", "WH", "ROLLBACK-PLAN", "CYCLE", List.of("LOC", "MISSING")));
                            // 模拟 Controller 的异常退出；关闭会话必须回滚此前已经插入的计划和范围。
                        }
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM count_plan", Integer.class));
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM count_scope", Integer.class));
                        var bucket = StockBucketKey.of("ENT", "WH", "OWNER", "LOC", "SKU", "NO_LOT", "GOOD");
                        try (var session = sessions.openSession(false)) {
                            new InventoryApplicationService(session, clock).receive("ENT", "WH", "RECEIVE", "DOC", "ACTOR", bucket, Quantity.parse("10", 0));
                            session.commit();
                        }
                        var fence = context.getBean(SpringFenceHandler.class);
                        var action = context.getBean(ReservationTccAction.class);
                        var branch = new BusinessActionContext();
                        branch.setXid("production-rollback"); branch.setBranchId(77L);
                        branch.setActionName(ReservationTccAction.ACTION_NAME); branch.setActionContext(new HashMap<>());
                        assertThrows(RuntimeException.class, () -> fence.prepareFence(branch.getXid(), branch.getBranchId(), ReservationTccAction.ACTION_NAME, () -> {
                            action.tryReserve(branch, "ENT", "WH", "ALLOC", "ATT", "a".repeat(64), 1L,
                                    List.of(new ReservationLineInput(bucket, Quantity.parse("2", 0), "LINE")));
                            throw new IllegalStateException("注入 Try 成功后的本地失败");
                        }));
                        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance", BigDecimal.class).signum());
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
                        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
                    });
        }
    }
}
