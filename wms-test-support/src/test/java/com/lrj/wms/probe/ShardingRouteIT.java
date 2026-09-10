package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL与ShardingSphere探针；Docker不可用直接失败，绝不静默跳过。 */
class ShardingRouteIT {
    private static MySQLContainer mysql;
    private static DataSource sharded;
    private static DataSource shardA;
    private static DataSource shardB;
    private static final org.apache.seata.rm.fence.SpringFenceHandler fence = new org.apache.seata.rm.fence.SpringFenceHandler();
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void prepare() throws Exception {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("bootstrap")
                .withUsername("probe").withPassword(UUID.randomUUID().toString());
        mysql.start();
        DataSource admin = source("bootstrap", "root");
        JdbcTemplate setup = new JdbcTemplate(admin);
        // 只对测试容器创建全新隔离库，不读取或修改dev-infra共享实例。
        for (String name : List.of("inventory_a", "inventory_b", "inbound", "outbound")) {
            setup.execute("CREATE DATABASE wms_s0_" + name);
            setup.execute("CREATE USER 'wms_s0_" + name + "'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
            setup.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON wms_s0_" + name + ".* TO 'wms_s0_" + name + "'@'%'");
            Flyway.configure().dataSource(source("wms_s0_" + name, "root"))
                    .locations("classpath:db/probe").load().migrate();
        }
        shardA = source("wms_s0_inventory_a", "wms_s0_inventory_a");
        shardB = source("wms_s0_inventory_b", "wms_s0_inventory_b");
        new JdbcTemplate(shardA).update("INSERT INTO stock_probe VALUES ('A','sku',100,0,0),('A','rollback',10,0,0),('A','rollback-other',10,0,0)");
        new JdbcTemplate(shardB).update("INSERT INTO stock_probe VALUES ('B','sku',100,0,0)");
        try (var stream = ShardingRouteIT.class.getResourceAsStream("/sharding-probe.yaml")) {
            sharded = YamlShardingSphereDataSourceFactory.createDataSource(
                    Map.of("ds_a", shardA, "ds_b", shardB), stream.readAllBytes());
        }
        Configuration config = new Configuration(new Environment("probe", new JdbcTransactionFactory(), sharded));
        config.addMapper(StockProbeMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        // 一个RM绑定一个物理数据源的基线；不能据此声称多数据源动态Fence已验证。
        org.apache.seata.rm.fence.SpringFenceHandler.setDataSource(shardA);
        org.apache.seata.rm.fence.SpringFenceHandler.setTransactionTemplate(
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(shardA)));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (sharded instanceof AutoCloseable closeable) closeable.close();
        if (mysql != null) mysql.stop();
    }

    /** 验证同仓两次变更整体回滚，且未触碰另一物理分片。 */
    @Test
    void sameWarehouseRollbackDoesNotLeakIntoOtherShard() {
        try (var session = sessions.openSession(false)) {
            var mapper = session.getMapper(StockProbeMapper.class);
            assertEquals(1, mapper.reserve("A", "rollback", 3));
            assertEquals(1, mapper.reserve("A", "rollback-other", 2));
            assertEquals(3, mapper.reserved("A", "rollback"));
            assertEquals(2, mapper.reserved("A", "rollback-other"));
            session.rollback();
        }
        assertEquals(0L, new JdbcTemplate(shardA).queryForObject("SELECT reserved FROM stock_probe WHERE sku_id='rollback'", Long.class));
        assertEquals(0L, new JdbcTemplate(shardA).queryForObject("SELECT reserved FROM stock_probe WHERE sku_id='rollback-other'", Long.class));
        assertEquals(0L, new JdbcTemplate(shardB).queryForObject("SELECT reserved FROM stock_probe WHERE sku_id='sku'", Long.class));
    }

    /** 200个竞争操作最多成功100次，检查数据库效果而非只检查HTTP返回。 */
    @Test
    void competingReservationsCannotGoNegative() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 200; i++) requests.add(() -> {
                try (var session = sessions.openSession(false)) {
                    int changed = session.getMapper(StockProbeMapper.class).reserve("A", "sku", 1);
                    session.commit();
                    return changed;
                }
            });
            int successes = 0;
            for (var result : pool.invokeAll(requests)) successes += result.get();
            assertEquals(100, successes);
        }
        assertEquals(100L, new JdbcTemplate(shardA).queryForObject("SELECT reserved FROM stock_probe WHERE sku_id='sku'", Long.class));
    }

    /** 缺仓写入与未知仓拒绝，跨服务账号没有读写库存表的权限。 */
    @Test
    void routingAndDatabasePermissionsRejectUnsafeAccess() throws Exception {
        try (Connection connection = sharded.getConnection(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE stock_probe SET reserved=0"));
        }
        try (var session = sessions.openSession(false)) {
            assertThrows(Exception.class, () -> session.getMapper(StockProbeMapper.class).reserve("UNKNOWN", "sku", 1));
            session.rollback();
        }
        for (String service : List.of("inbound", "outbound")) {
            JdbcTemplate isolated = new JdbcTemplate(source("wms_s0_" + service, "wms_s0_" + service));
            assertThrows(Exception.class, () -> isolated.queryForList("SELECT * FROM wms_s0_inventory_a.stock_probe"));
            assertThrows(Exception.class, () -> isolated.update("UPDATE wms_s0_inventory_a.stock_probe SET reserved=0"));
        }
    }

    /** 通过Boot的MyBatis自动配置连接分片数据源，排除只用原生会话掩盖装配不兼容。 */
    @Test
    void bootMybatisAutoConfigurationUsesShardedDataSource() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> sharded,
                        definition -> definition.setDestroyMethodName(""))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var factory = context.getBean(SqlSessionFactory.class);
                    factory.getConfiguration().addMapper(StockProbeMapper.class);
                    try (var session = factory.openSession(false)) {
                        assertEquals(0, session.getMapper(StockProbeMapper.class).reserved("B", "sku"));
                        session.rollback();
                    }
                });
    }

    /** 验证Seata原生Fence与库存SQL共享真实事务，回调异常不留阶段或数量残留。 */
    @Test
    void fenceAndStockRollbackTogether() {
        JdbcTemplate db = new JdbcTemplate(shardA);
        assertThrows(RuntimeException.class, () -> fence.prepareFence("atomic", 101L, "probe", () -> {
            db.update("UPDATE stock_probe SET reserved=reserved+1 WHERE warehouse_id='A' AND sku_id='rollback'");
            throw new IllegalStateException("注入Try业务失败");
        }));
        assertEquals(0L, db.queryForObject("SELECT reserved FROM stock_probe WHERE sku_id='rollback'", Long.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid='atomic'", Long.class));
    }

    /** 两次Confirm只执行一次业务回调，空回滚后晚Try被原生Fence拒绝。 */
    @Test
    void fenceSecondPhaseDeduplicatesAndRejectsLateTry() throws Exception {
        var callback = new FenceCallback();
        var confirm = FenceCallback.class.getMethod("confirm");
        var cancel = FenceCallback.class.getMethod("cancel");
        assertEquals(true, fence.prepareFence("confirm", 102L, "probe", () -> true));
        assertTrue(fence.commitFence(confirm, callback, "confirm", 102L, new Object[0]));
        assertTrue(fence.commitFence(confirm, callback, "confirm", 102L, new Object[0]));
        assertEquals(1, callback.confirmCalls);
        assertTrue(fence.rollbackFence(cancel, callback, "empty", 103L, new Object[0], "probe"));
        assertThrows(RuntimeException.class, () -> fence.prepareFence("empty", 103L, "probe", () -> {
            fail("空回滚后不能执行Try业务");
            return true;
        }));
        assertEquals(0, callback.cancelCalls);
    }

    /** 反射调用的隔离业务回调，不连接TC，不作为全局事务验收。 */
    public static class FenceCallback {
        int confirmCalls;
        int cancelCalls;
        /** 记录实际Confirm副作用次数。 */
        public boolean confirm() { confirmCalls++; return true; }
        /** 空回滚不应执行此回调。 */
        public boolean cancel() { cancelCalls++; return true; }
    }

    private static DataSource source(String database, String user) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false");
        ds.setUser(user);
        ds.setPassword(mysql.getPassword());
        return ds;
    }
}
