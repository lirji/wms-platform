package com.lrj.wms.probe;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.ServerSocket;
import java.time.Duration;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.TMClient;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import static org.junit.jupiter.api.Assertions.*;

/** 真实TC终态查询能力探针；没有业务RM，不能作为跨仓事务通过证据。 */
class TcTerminalEvidenceIT {
    /** 提交与回滚清理后是否均变为Finished，决定恢复协议能否依赖单独getStatus。 */
    @Test
    void clearedSessionsDoNotProvideRecoverableCommitEvidence() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        final int tcPort = port;
        // 临时端口竞争会让测试失败；不得改绑到其他项目端口或关闭冲突进程。
        try (var tc = new GenericContainer<>("apache/seata-server:2.6.0")
                .withEnv("SEATA_IP", "127.0.0.1").withEnv("SEATA_PORT", Integer.toString(tcPort))
                .withEnv("STORE_MODE", "file").withEnv("JAVA_OPTS", "-Xms128m -Xmx256m")
                .withExposedPorts(tcPort)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", tcPort), new ExposedPort(tcPort))))
                .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3))) {
            tc.start();
            System.setProperty("service.vgroupMapping.wms_s0_group", "default");
            System.setProperty("service.default.grouplist", "127.0.0.1:" + tcPort);
            TMClient.init("wms-s0-terminal-probe", "wms_s0_group");
            var committed = GlobalTransactionContext.createNew();
            committed.begin(30000, "s0-commit");
            String committedXid = committed.getXid();
            committed.commit();
            assertEquals(GlobalStatus.Committed, committed.getLocalStatus());
            var rolledBack = GlobalTransactionContext.createNew();
            rolledBack.begin(30000, "s0-rollback");
            String rolledBackXid = rolledBack.getXid();
            rolledBack.rollback();
            assertEquals(GlobalStatus.Rollbacked, rolledBack.getLocalStatus());
            awaitFinished(committedXid);
            awaitFinished(rolledBackXid);
            // 本断言证明候选查询接口的限制，不将Finished解释为业务成功。
            System.out.println("TC_PROBE: commit and rollback both query as Finished after cleanup; durable terminal evidence still required");
        }
    }

    private static void awaitFinished(String xid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        GlobalStatus status;
        do {
            status = GlobalTransactionContext.reload(xid).getStatus();
            if (status == GlobalStatus.Finished) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("TC未在探针时间窗内清理会话: " + status);
    }
}
