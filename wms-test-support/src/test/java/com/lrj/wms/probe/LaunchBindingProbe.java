package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实MySQL竞争和真实TC begin验证启动CAS；不冒充正式TM进程崩溃或HTTP协议验收。 */
final class LaunchBindingProbe {
    static void verify(MySQLContainer mysql) throws Exception {
        var source = new MysqlDataSource();
        source.setURL(mysql.getJdbcUrl()); source.setUser("root"); source.setPassword(mysql.getPassword());
        new JdbcTemplate(source).execute("CREATE DATABASE s0_launch");
        source.setURL(mysql.getJdbcUrl().replace("/seata", "/s0_launch"));
        Flyway.configure().dataSource(source).locations("classpath:db/launch-probe").load().migrate();
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var config = new Configuration(new Environment("launch", new SpringManagedTransactionFactory(), source));
        config.addMapper(LaunchProbeMapper.class);
        var mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(LaunchProbeMapper.class);
        db.update("INSERT INTO allocation_slot VALUES ('enterprise','allocation',NULL)");
        // 16个候选尝试同时争同一个业务槽，仅胜者在同事务内创建尝试。
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(16)) {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 16; i++) {
                String attempt = "attempt-" + i;
                futures.add(pool.submit(() -> { start.await(); return tx.execute(status -> {
                    int changed = mapper.activate("enterprise", "allocation", attempt);
                    if (changed == 1) mapper.insert("enterprise", "allocation", attempt, 1);
                    return changed;
                }); }));
            }
            start.countDown();
            int winners = 0;
            for (var future : futures) winners += future.get(15, TimeUnit.SECONDS);
            assertEquals(1, winners);
        }
        String attempt = db.queryForObject("SELECT active_attempt_id FROM allocation_slot", String.class);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM launch_attempt", Integer.class));
        // 两启动执行器并发CAS，仅一个能领取；不是用Java锁串行化数据库竞争。
        var startClaim = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { startClaim.await(); return mapper.claim("enterprise", attempt, "owner-a", 0, 0); });
            var b = pool.submit(() -> { startClaim.await(); return mapper.claim("enterprise", attempt, "owner-b", 0, 0); });
            startClaim.countDown();
            assertEquals(1, a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS));
        }
        String owner = db.queryForObject("SELECT launch_owner FROM launch_attempt", String.class);
        var original = GlobalTransactionContext.createNew();
        original.begin(60000, "s0-launch-original");
        String orphanXid = original.getXid();
        RootContext.unbind();
        // begin之后绑定之前失联：仅在没有绑定且入口证明存在时允许提升代际。
        db.update("UPDATE launch_attempt SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertEquals(1, mapper.fenceUnbound("enterprise", attempt, 0, 1));
        assertEquals(0, mapper.bind("enterprise", attempt, owner, 0, 1, orphanXid));
        original.rollback(); // 只清理自有已知空XID，不能清理另一个执行器绑定的XID。
        assertEquals(1, mapper.claim("enterprise", attempt, "recovered", 1, 2));
        var recovered = GlobalTransactionContext.createNew();
        recovered.begin(60000, "s0-launch-recovered");
        String boundXid = recovered.getXid();
        Integer bound = tx.execute(status -> mapper.bind("enterprise", attempt, "recovered", 1, 3, boundXid));
        assertEquals(Integer.valueOf(1), bound);
        // 模拟本地提交后返回丢失：重放CAS为0，但权威读能恢复同一XID，不能再次begin。
        assertEquals(0, mapper.bind("enterprise", attempt, "recovered", 1, 3, boundXid));
        assertEquals(boundXid, mapper.boundXid("enterprise", attempt));
        db.update("UPDATE launch_attempt SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND)");
        assertEquals(0, mapper.fenceUnbound("enterprise", attempt, 1, 4));
        assertEquals(0, mapper.bind("enterprise", attempt, "recovered", 1, 4, orphanXid));
        assertNull(mapper.boundXid("foreign-enterprise", attempt));
        assertEquals(0, mapper.claim("foreign-enterprise", attempt, "intruder", 1, 4));
        recovered.rollback();
        // 活动槽更新后插入失败必须一起回滚，不能遗留指向不存在尝试的槽。
        db.update("INSERT INTO allocation_slot VALUES ('enterprise','rollback-slot',NULL)");
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> tx.execute(status -> {
            mapper.activate("enterprise", "rollback-slot", "missing-attempt");
            mapper.insert("enterprise", "rollback-slot", attempt, 1);
            return null;
        }));
        assertNull(db.queryForObject("SELECT active_attempt_id FROM allocation_slot WHERE allocation_id='rollback-slot'", String.class));
        // 租约过期但没有受控入口证明，必须保持待恢复，不能借机发起新代际。
        tx.executeWithoutResult(status -> { mapper.activate("enterprise", "rollback-slot", "unproven"); mapper.insert("enterprise", "rollback-slot", "unproven", 0); });
        assertEquals(1, mapper.claim("enterprise", "unproven", "unknown", 0, 0));
        db.update("UPDATE launch_attempt SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE attempt_id='unproven'");
        assertEquals(0, mapper.fenceUnbound("enterprise", "unproven", 0, 1));
        System.out.println("LAUNCH_PROBE: one active attempt, one launch winner, fenced old epoch, immutable bound XID, lost binding response recovered, unproven lease recovery denied");
    }
}
