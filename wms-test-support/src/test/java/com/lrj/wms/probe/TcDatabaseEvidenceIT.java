package com.lrj.wms.probe;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 独立TC/MySQL审计、启动CAS、重复Try所有权、双仓回调及独立RM进程探针；不构成正式跨仓业务验收。 */
class TcDatabaseEvidenceIT {
    /** 会话清理和TC重启后，应从数据库区分提交与回滚，不依赖TM进程内存。 */
    @Test
    void terminalAuditSurvivesSessionCleanupAndTcRestart() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        final int tcPort = port;
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("seata")
                     .withUsername("probe").withPassword(UUID.randomUUID().toString())
                     ) {
            mysql.start();
            var source = new MysqlDataSource();
            source.setURL(mysql.getJdbcUrl());
            source.setUser("root");
            source.setPassword(mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/tc-probe").load().migrate();
            var jdbc = new JdbcTemplate(source);
            // 测试账户仅对隔离seata库授权；不触碰共享组件和生产TC。
            try (var tc = new GenericContainer<>("apache/seata-server:2.6.0")
                    .withEnv("SEATA_IP", "127.0.0.1").withEnv("SEATA_PORT", Integer.toString(tcPort))
                    .withEnv("SEATA_SERVER_RETRY_DEAD_THRESHOLD", "1000")
                    .withEnv("STORE_MODE", "db").withEnv("JAVA_OPTS", "-Xms128m -Xmx256m")
                    .withEnv("SEATA_STORE_DB_DATASOURCE", "druid")
                    .withEnv("SEATA_STORE_DB_DB_TYPE", "mysql")
                    .withEnv("SEATA_STORE_DB_DRIVER_CLASS_NAME", "com.mysql.cj.jdbc.Driver")
                    .withEnv("SEATA_STORE_DB_URL", "jdbc:mysql://" + mysql.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress()
                            + ":3306/seata?allowPublicKeyRetrieval=true&useSSL=false")
                    .withEnv("SEATA_STORE_DB_USER", mysql.getUsername())
                    .withEnv("SEATA_STORE_DB_PASSWORD", mysql.getPassword())
                    .withExposedPorts(tcPort)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", tcPort), new ExposedPort(tcPort))))
                    .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3))) {
                tc.start();
                System.setProperty("service.vgroupMapping.wms_s0_group", "default");
                System.setProperty("service.default.grouplist", "127.0.0.1:" + tcPort);
                TMClient.init("wms-s0-db-probe", "wms_s0_group");
                LaunchBindingProbe.verify(mysql);
                var committed = GlobalTransactionContext.createNew();
                committed.begin(30000, "s0-db-commit");
                String commitXid = committed.getXid();
                // 确认实际使用DB模式，防止环境变量未生效导致假验证。
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM global_table WHERE xid=?", Integer.class, commitXid));
                committed.commit();
                assertEquals(GlobalStatus.Committed, committed.getLocalStatus());
                var rolledBack = GlobalTransactionContext.createNew();
                rolledBack.begin(30000, "s0-db-rollback");
                String rollbackXid = rolledBack.getXid();
                rolledBack.rollback();
                awaitEvidenceAndCleanup(jdbc, commitXid, 9);
                awaitEvidenceAndCleanup(jdbc, rollbackXid, 11);
                assertEquals(GlobalStatus.Finished, GlobalTransactionContext.reload(commitXid).getStatus());
                assertEquals(GlobalStatus.Finished, GlobalTransactionContext.reload(rollbackXid).getStatus());
                // 重启本测试拥有的TC，数据库仍保留；不把断连重试日志当成验证结果。
                tc.getDockerClient().restartContainerCmd(tc.getContainerId()).exec();
                Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(45)).waitUntilReady(tc);
                awaitTcQuery(commitXid);
                awaitEvidenceAndCleanup(jdbc, commitXid, 9);
                awaitEvidenceAndCleanup(jdbc, rollbackXid, 11);
                assertEquals("wms_s0_group", jdbc.queryForObject(
                        "SELECT transaction_service_group FROM terminal_evidence WHERE xid=?", String.class, commitXid));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM terminal_evidence WHERE xid=?", Integer.class, "missing-xid"));
                verifyAuditFailureRecovery(jdbc);
                new TwoWarehouseTccProbe(mysql).verify(jdbc, tc);
                new DuplicateTryProbe(mysql).verify(jdbc);
                IndependentRmProbe.verify(mysql, jdbc, "127.0.0.1:" + tcPort);
                System.out.println("TC_DB_PROBE: persisted terminal audit, TC/RM restart, launch CAS and duplicate Try ownership verified; full business acceptance pending");
            }
        }
    }

    /** 只有重启后的TC再次响应查询，才把重启列为已完成验证。 */
    private static void awaitTcQuery(String xid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        Exception last = null;
        do {
            try {
                if (GlobalTransactionContext.reload(xid).getStatus() == GlobalStatus.Finished) return;
            } catch (Exception disconnected) { last = disconnected; }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        fail("重启后的TC未恢复查询", last);
    }

    /** 模拟审计写入失败，确认终态落盘与证据原子失败，并由TC自行恢复。 */
    private static void verifyAuditFailureRecovery(JdbcTemplate jdbc) throws Exception {
        // 故障只注入本测试新建的审计表；触发器撤销不能触碰共享实例。
        jdbc.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON terminal_evidence FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='s0 injected audit failure'");
        String xid;
        try {
            var transaction = GlobalTransactionContext.createNew();
            transaction.begin(30000, "s0-audit-failure");
            xid = transaction.getXid();
            transaction.commit();
            // 等待多个后台恢复周期，不能仅在异步写入尚未发生时断言零记录。
            Thread.sleep(3500);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM terminal_evidence WHERE xid=?", Integer.class, xid));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM global_table WHERE xid=? AND status NOT IN (9,11,13)", Integer.class, xid));
        } finally {
            jdbc.execute("DROP TRIGGER reject_audit");
        }
        awaitEvidenceAndCleanup(jdbc, xid, 9);
    }

    private static void awaitEvidenceAndCleanup(JdbcTemplate jdbc, String xid, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            var statuses = jdbc.queryForList("SELECT terminal_status FROM terminal_evidence WHERE xid=?", Integer.class, xid);
            if (!statuses.isEmpty()) {
                assertEquals(expected, statuses.getFirst());
                if (jdbc.queryForObject("SELECT COUNT(*) FROM global_table WHERE xid=?", Integer.class, xid) == 0) return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("TC终态证据或会话清理未在30秒内完成: " + xid
                + ", session=" + jdbc.queryForList("SELECT status FROM global_table WHERE xid=?", xid)
                + ", audit=" + jdbc.queryForList("SELECT terminal_status FROM terminal_evidence WHERE xid=?", xid));
    }
}
