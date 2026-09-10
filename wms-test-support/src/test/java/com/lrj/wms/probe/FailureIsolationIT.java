package com.lrj.wms.probe;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 只操作本测试创建的 MySQL/TC；缺终态证据不得写 ALLOCATED；禁止动共享 dev-infra。
 */
class FailureIsolationIT {
    @Test
    void ownedTcCrashKeepsBarrierPendingAndDoesNotTouchSharedInfra() throws Exception {
        assertTrue(DockerClientFactory.instance().isDockerAvailable(), "failure-it 需要 Docker，禁止 skip");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        final int tcPort = port;
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("seata")
                     .withUsername("probe").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            var guard = OwnedContainerGuard.snapshotShared(mysql.getDockerClient());
            var source = new MysqlDataSource();
            source.setURL(mysql.getJdbcUrl());
            source.setUser("root");
            source.setPassword(mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/tc-probe").load().migrate();
            try (var tc = seata(mysql, tcPort)) {
                tc.start();
                guard.registerOwned(mysql.getContainerId(), tc.getContainerId());
                guard.refuseForeignAndShared();
                System.setProperty("service.vgroupMapping.wms_s0_group", "default");
                System.setProperty("service.default.grouplist", "127.0.0.1:" + tcPort);
                TMClient.init("wms-s0-db-probe", "wms_s0_group");
                var probe = new BusinessBarrierProbe(mysql);

                String pendingAttempt = "failure-pending";
                var pending = GlobalTransactionContext.createNew();
                pending.begin(60000, "s0-failure-pending");
                probe.tryAllocate(pendingAttempt, pending.getXid());
                assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING, probe.decision(pendingAttempt));
                assertEquals(0, probe.release(pendingAttempt));
                assertEquals(0, probe.outbox(pendingAttempt));
                RootContext.unbind();

                String committedAttempt = "failure-committed";
                var committed = GlobalTransactionContext.createNew();
                committed.begin(60000, "s0-failure-committed");
                probe.tryAllocate(committedAttempt, committed.getXid());
                committed.commit();
                await(() -> probe.decision(committedAttempt) == TerminalEvidenceAdapter.Decision.ALLOW_ALLOCATED);
                assertEquals(1, probe.release(committedAttempt));

                guard.kill(tc.getContainerId());
                assertEquals(TerminalEvidenceAdapter.Decision.RECOVERY_PENDING, probe.decision(pendingAttempt),
                        "未提交attempt在TC被杀后仍不得放行");
                assertEquals(0, probe.release(pendingAttempt));
                assertEquals(TerminalEvidenceAdapter.Decision.ALLOW_ALLOCATED, probe.decision(committedAttempt),
                        "证据已在审计库后，TC宕机不得取消已证明的放行");
                assertEquals(1, probe.outbox(committedAttempt), "不得因TC宕机重复写Outbox");
                assertThrows(IllegalStateException.class, probe::refuseXxlPhaseTwo);
                guard.assertSharedUntouched();
                System.out.println("FAILURE_IT: owned MySQL/TC only; missing evidence=RECOVERY_PENDING; "
                        + "persisted evidence survives TC kill; shared dev-infra untouched");
            } finally {
                guard.assertSharedUntouched();
            }
        }
    }

    private static GenericContainer<?> seata(MySQLContainer mysql, int tcPort) {
        return new GenericContainer<>("apache/seata-server:2.6.0")
                .withEnv("SEATA_IP", "127.0.0.1").withEnv("SEATA_PORT", Integer.toString(tcPort))
                .withEnv("SEATA_SERVER_RETRY_DEAD_THRESHOLD", "1000")
                .withEnv("STORE_MODE", "db").withEnv("JAVA_OPTS", "-Xms128m -Xmx256m")
                .withEnv("SEATA_STORE_DB_DATASOURCE", "druid")
                .withEnv("SEATA_STORE_DB_DB_TYPE", "mysql")
                .withEnv("SEATA_STORE_DB_DRIVER_CLASS_NAME", "com.mysql.cj.jdbc.Driver")
                .withEnv("SEATA_STORE_DB_URL", "jdbc:mysql://"
                        + mysql.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress()
                        + ":3306/seata?allowPublicKeyRetrieval=true&useSSL=false")
                .withEnv("SEATA_STORE_DB_USER", mysql.getUsername())
                .withEnv("SEATA_STORE_DB_PASSWORD", mysql.getPassword())
                .withExposedPorts(tcPort)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", tcPort), new ExposedPort(tcPort))))
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("failure-it 在30秒内未等到终态证据");
    }
}
