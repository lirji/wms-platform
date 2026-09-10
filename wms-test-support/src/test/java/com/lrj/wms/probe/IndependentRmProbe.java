package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 两个独立RM进程的故障夹具；数据库及进程均由本次测试独占。 */
final class IndependentRmProbe {
    /** 在部分确认窗口杀掉B进程，再由新B进程恢复同XID/branch；不重新Try。 */
    static void verify(MySQLContainer mysql, JdbcTemplate tcDatabase, String tcAddress) throws Exception {
        var admin = new JdbcTemplate(source(mysql, "seata", "root"));
        for (String warehouse : new String[]{"A", "B"}) {
            String database = "s0_process_" + warehouse;
            admin.execute("CREATE DATABASE " + database);
            admin.execute("CREATE USER '" + database + "'@'%' IDENTIFIED BY '" + mysql.getPassword() + "'");
            admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON " + database + ".* TO '" + database + "'@'%'");
            Flyway.configure().dataSource(source(mysql, database, "root"))
                    .locations("classpath:db/probe", "classpath:db/tcc-warehouse").load().migrate();
            new JdbcTemplate(source(mysql, database, database)).update("INSERT INTO stock_probe VALUES (?, 'sku',100,0,0)", warehouse);
        }
        JdbcTemplate a = new JdbcTemplate(source(mysql, "s0_process_A", "s0_process_A"));
        JdbcTemplate b = new JdbcTemplate(source(mysql, "s0_process_B", "s0_process_B"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> a.queryForList("SELECT * FROM s0_process_B.stock_probe"));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> b.queryForList("SELECT * FROM s0_process_A.stock_probe"));
        try (var rmA = new Child(mysql, "A", tcAddress, false, "a");
             var rmB = new Child(mysql, "B", tcAddress, true, "b-failing")) {
            var transaction = GlobalTransactionContext.createNew();
            transaction.begin(60000, "s0-independent-rm-commit");
            String xid = transaction.getXid();
            long branchA = rmA.prepare(xid);
            long branchB = rmB.prepare(xid);
            assertNotEquals(branchA, branchB);
            transaction.commit();
            await(() -> effect(a, xid, "CONFIRM") == 1);
            assertEquals(0, effect(b, xid, "CONFIRM"));
            assertEquals(1, fence(b, xid, branchB, 1));
            assertEquals(0, tcDatabase.queryForObject("SELECT COUNT(*) FROM terminal_evidence WHERE xid=?", Integer.class, xid));
            // 仅杀掉本夹具启动并持有Process句柄的B，不按端口/名称终止其他进程。
            rmB.crash();
            assertTrue(rmA.process.isAlive());
            assertEquals(30L, reserved(a));
            assertEquals(30L, reserved(b));
            try (var recoveredB = new Child(mysql, "B", tcAddress, false, "b-recovered")) {
                assertNotEquals(rmB.process.pid(), recoveredB.process.pid());
                await(() -> effect(b, xid, "CONFIRM") == 1);
                await(() -> tcDatabase.queryForObject("SELECT COUNT(*) FROM terminal_evidence WHERE xid=? AND terminal_status=9", Integer.class, xid) == 1);
                assertEquals(1, effect(a, xid, "CONFIRM"));
                assertEquals(1, fence(a, xid, branchA, 2));
                assertEquals(1, fence(b, xid, branchB, 2));
                assertEquals(1, b.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid=?", Integer.class, xid));
                var rollback = GlobalTransactionContext.createNew();
                rollback.begin(60000, "s0-independent-rm-cancel");
                String rollbackXid = rollback.getXid();
                rmA.prepare(rollbackXid);
                recoveredB.prepare(rollbackXid);
                rollback.rollback();
                await(() -> effect(a, rollbackXid, "CANCEL") == 1 && effect(b, rollbackXid, "CANCEL") == 1);
                assertEquals(30L, reserved(a));
                assertEquals(30L, reserved(b));
                // 让新Try确定地库存不足，检查ShardingSphere下Fence插入与库存事务整体回滚。
                a.update("UPDATE stock_probe SET on_hand=30");
                var insufficient = GlobalTransactionContext.createNew();
                insufficient.begin(60000, "s0-independent-rm-insufficient");
                String rejectedXid = insufficient.getXid();
                rmA.rejectPrepare(rejectedXid);
                assertEquals(0, a.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid=?", Integer.class, rejectedXid));
                assertEquals(1, tcDatabase.queryForObject("SELECT COUNT(*) FROM branch_table WHERE xid=?", Integer.class, rejectedXid));
                insufficient.rollback();
                await(() -> a.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND status=4", Integer.class, rejectedXid) == 1);
                assertEquals(0, effect(a, rejectedXid, "CANCEL"));
                assertEquals(30L, reserved(a));
            }
        }
        System.out.println("INDEPENDENT_RM_PROBE: two JVMs, per-Cell ShardingSphere and native Fence; B crash/restart resumes original branch; A effect once; cancel quantities preserved");
    }

    private static int effect(JdbcTemplate db, String xid, String phase) {
        return db.queryForObject("SELECT COUNT(*) FROM callback_effect WHERE xid=? AND phase=?", Integer.class, xid, phase);
    }
    private static int fence(JdbcTemplate db, String xid, long branch, int status) {
        return db.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND branch_id=? AND status=?", Integer.class, xid, branch, status);
    }
    private static long reserved(JdbcTemplate db) { return db.queryForObject("SELECT reserved FROM stock_probe", Long.class); }
    private static DataSource source(MySQLContainer mysql, String database, String user) {
        var source = new MysqlDataSource();
        source.setURL("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false");
        source.setUser(user);
        source.setPassword(mysql.getPassword());
        return source;
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        do { if (condition.getAsBoolean()) return; Thread.sleep(100); } while (System.nanoTime() < deadline);
        fail("独立RM恢复在90秒内未收敛");
    }

    /** 限定进程、内存、响应队列、日志及等待时间，异常退出同样回收子进程。 */
    private static final class Child implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final ArrayBlockingQueue<String> responses = new ArrayBlockingQueue<>(8);
        private final Path log;
        private final Thread reader;

        Child(MySQLContainer mysql, String warehouse, String tcAddress, boolean reject, String label) throws Exception {
            log = Path.of("target", "failsafe-reports", "rm-" + label + ".log");
            Files.createDirectories(log.getParent());
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xms64m", "-Xmx256m", "-cp", classpath, WarehouseRmProcess.class.getName()).redirectErrorStream(true);
            builder.environment().putAll(Map.of("PROBE_WAREHOUSE", warehouse,
                    "PROBE_JDBC_URL", "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306)
                            + "/s0_process_" + warehouse + "?allowPublicKeyRetrieval=true&useSSL=false",
                    "PROBE_DB_USER", "s0_process_" + warehouse, "PROBE_DB_PASSWORD", mysql.getPassword(),
                    "PROBE_TC_ADDRESS", tcAddress, "PROBE_REJECT", Boolean.toString(reject)));
            process = builder.start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = Thread.ofPlatform().daemon().name("rm-probe-output-" + label).start(() -> {
                try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                     var file = Files.newBufferedWriter(log)) {
                    int retained = 0;
                    String line;
                    while ((line = output.readLine()) != null) {
                        if (retained < 1_000_000) { file.write(line); file.newLine(); file.flush(); retained += line.length(); }
                        if (line.startsWith("WMS_")) responses.offer(line);
                    }
                } catch (Exception failure) { responses.offer("WMS_ERROR output-reader"); }
                finally { responses.offer("WMS_EXIT"); }
            });
            try { assertEquals("WMS_READY", response()); }
            catch (Throwable failure) { close(); throw failure; }
        }
        long prepare(String xid) throws Exception {
            input.write("PREPARE " + xid); input.newLine(); input.flush();
            String result = response();
            assertTrue(result.startsWith("WMS_RESULT "), () -> "RM未返回Try结果，见" + log + ": " + result);
            return Long.parseLong(result.substring(11));
        }
        void rejectPrepare(String xid) throws Exception {
            input.write("PREPARE " + xid); input.newLine(); input.flush();
            assertEquals("WMS_REJECTED INSUFFICIENT", response());
        }
        private String response() throws Exception {
            String result = responses.poll(60, TimeUnit.SECONDS);
            assertNotNull(result, () -> "RM响应超时，见" + log);
            return result;
        }
        void crash() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "测试RM未退出");
        }
        /** 优先结束stdin，超时只强制回收自身句柄所指向的测试进程。 */
        @Override public void close() throws Exception {
            try { input.close(); } catch (java.io.IOException closedPipe) { /* 崩溃后管道可能已关闭，仍必须回收进程。 */ }
            if (!process.waitFor(5, TimeUnit.SECONDS)) crash();
            reader.join(1000);
        }
    }
}
