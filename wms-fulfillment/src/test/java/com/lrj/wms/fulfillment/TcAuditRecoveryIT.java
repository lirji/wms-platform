package com.lrj.wms.fulfillment;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实TC持久化证据接履约恢复；仓确认由夹具提供，不冒充完整库存RM业务链路。 */
class TcAuditRecoveryIT {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private static final TcEvidenceScope SCOPE = new TcEvidenceScope("test-cell", "wms-fulfillment", "wms_recovery_group");
    private static MySQLContainer business;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        business = new MySQLContainer("mysql:8.4.11")
                // 宿主端口误连其他服务时必须有握手读取上限，启动重试重新分配隔离端口。
                .withUrlParam("connectTimeout","3000").withUrlParam("socketTimeout","5000")
                .withStartupAttempts(2).withStartupTimeout(java.time.Duration.ofSeconds(45)).withDatabaseName("fulfillment")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        business.start();
        var ds = source(business, "wms");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/fulfillment").load().migrate();
        jdbc = new JdbcTemplate(ds);
        sessions = factory(ds, FulfillmentMapper.class, AllocationRecoveryMapper.class);
    }
    @AfterAll static void close() { if (business != null) business.stop(); }

    @Test
    void realTcAuditSurvivesTcStopAndReadOnlyAdapterReleasesOnlyCommittedAttempt() throws Exception {
        try (var auditDb = new MySQLContainer("mysql:8.4.11")
                // 宿主端口误连其他服务时必须有握手读取上限，启动重试重新分配隔离端口。
                .withUrlParam("connectTimeout","3000").withUrlParam("socketTimeout","5000")
                .withStartupAttempts(2).withStartupTimeout(java.time.Duration.ofSeconds(45)).withDatabaseName("seata")
                .withUsername("tc").withPassword(UUID.randomUUID().toString())) {
            auditDb.start();
            var adminSource = source(auditDb, "root");
            Flyway.configure().dataSource(adminSource).locations("classpath:db/tc-probe").load().migrate();
            var admin = new JdbcTemplate(adminSource);
            // 密码为本测试随机UUID，只有本测试容器允许创建账号和授予单表SELECT。
            admin.execute("CREATE USER 'tc_audit'@'%' IDENTIFIED BY '" + auditDb.getPassword() + "'");
            admin.execute("GRANT SELECT ON seata.terminal_evidence TO 'tc_audit'@'%'");
            var auditSource = source(auditDb, "tc_audit");
            assertThrows(org.springframework.dao.DataAccessException.class, () -> new JdbcTemplate(auditSource)
                    .update("DELETE FROM terminal_evidence WHERE xid='never'"));
            var auditSessions = factory(auditSource, TcEvidenceMapper.class);
            var env = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("wms.tc.audit.jdbc-url", auditDb.getJdbcUrl().split("\\?",2)[0])
                    .withProperty("wms.tc.audit.username", "tc_audit")
                    .withProperty("wms.tc.audit.password", auditDb.getPassword());
            try (var port = new TcEvidenceConfiguration().tcStatusPort(env, SCOPE)) {
            assertEquals("UP", port.health().getStatus().getCode());
            final int tcPort = freeTcPort();
            String committedXid, rollbackXid, commitAttempt, rollbackAttempt;
            try (var tc = new GenericContainer<>("apache/seata-server:2.6.0")
                    .withEnv("SEATA_IP", "127.0.0.1").withEnv("SEATA_PORT", String.valueOf(tcPort))
                    // 仅隔离测试缩短终态异步清理延迟，不将这个时间窗承诺为生产RTO。
                    .withEnv("SEATA_SERVER_RETRY_DEAD_THRESHOLD", "1000")
                    .withEnv("STORE_MODE", "db").withEnv("JAVA_OPTS", "-Xms128m -Xmx256m")
                    .withEnv("SEATA_STORE_DB_DATASOURCE", "druid").withEnv("SEATA_STORE_DB_DB_TYPE", "mysql")
                    .withEnv("SEATA_STORE_DB_DRIVER_CLASS_NAME", "com.mysql.cj.jdbc.Driver")
                    .withEnv("SEATA_STORE_DB_URL", "jdbc:mysql://" + auditDb.getContainerInfo().getNetworkSettings()
                            .getNetworks().get("bridge").getIpAddress() + ":3306/seata?allowPublicKeyRetrieval=true&useSSL=false")
                    .withEnv("SEATA_STORE_DB_USER", "tc").withEnv("SEATA_STORE_DB_PASSWORD", auditDb.getPassword())
                    .withExposedPorts(tcPort).withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", tcPort), new ExposedPort(tcPort))))
                    .waitingFor(Wait.forListeningPort()).withStartupTimeout(Duration.ofMinutes(3))) {
                tc.start();
                try (var tm = new SeataTmDriver(SCOPE,List.of("127.0.0.1:"+tcPort),null,null)) {
                    committedXid = tm.begin("real-audit-commit",30000);
                    assertNull(org.apache.seata.core.context.RootContext.getXID(),"返回的XID必须显式落库，不泄漏线程上下文");
                    assertEquals("wms-fulfillment",admin.queryForObject("SELECT application_id FROM global_table WHERE xid=?",String.class,committedXid));
                    org.apache.seata.core.context.RootContext.bind(committedXid);
                    assertEquals("TM_CONTEXT_ALREADY_BOUND",assertThrows(FulfillmentException.class,()->tm.begin("nested",30000)).code());
                    assertEquals(committedXid,org.apache.seata.core.context.RootContext.unbind());
                    commitAttempt = attempt("TC-REAL", committedXid, true);
                    assertTrue(port.read(committedXid).isEmpty());
                    new AllocationRecoverySweep(sessions, port, SCOPE, CLOCK).execute("TC-REAL");
                    assertEquals("TCC_TRYING", state(commitAttempt));
                    tm.commit(committedXid);
                    assertNull(org.apache.seata.core.context.RootContext.getXID());
                    rollbackXid = tm.begin("real-audit-rollback",30000);
                    rollbackAttempt = attempt("TC-REAL", rollbackXid, true);
                    tm.rollback(rollbackXid);
                    awaitTerminal(port, committedXid, "Committed");
                    awaitTerminal(port, rollbackXid, "Rollbacked");
                    assertEquals(GlobalStatus.Finished, GlobalTransactionContext.reload(committedXid).getStatus());
                }
            }
            // 真实TC已停；新的适配器/恢复对象仍只依据持久化终态读取，非TM本地状态。
            var restarted = new JdbcTcStatusPort(auditSessions, SCOPE, null);
            for (int i = 0; i < 3; i++) new AllocationRecoverySweep(sessions, restarted, SCOPE, CLOCK).execute("TC-REAL");
            assertEquals("ALLOCATED", state(commitAttempt));
            assertEquals("TCC_TRYING", state(rollbackAttempt));
            assertEquals(3, countOutbox(commitAttempt));
            assertEquals(0, countOutbox(rollbackAttempt));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND event_type='TcTerminalNoticeV1'",Integer.class,commitAttempt));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND event_type='TcTerminalNoticeV1'",Integer.class,rollbackAttempt));
            assertTrue(port.read("not-a-real-xid").isEmpty());
            assertEquals("TC_EVIDENCE_IDENTITY_MISMATCH", assertThrows(FulfillmentException.class,
                    () -> new JdbcTcStatusPort(auditSessions,
                            new TcEvidenceScope("test-cell", "wms-fulfillment", "wrong_group"), null)
                            .read(committedXid)).code());
            // SELECT授权撤销/恢复必须呈现真实不可用，不得返回伪造Committed或静默成功。
            admin.execute("REVOKE SELECT ON seata.terminal_evidence FROM 'tc_audit'@'%'");
            assertEquals("TC_AUDIT_UNAVAILABLE", assertThrows(FulfillmentException.class, () -> port.read(committedXid)).code());
            admin.execute("GRANT SELECT ON seata.terminal_evidence TO 'tc_audit'@'%'");
            assertEquals("Committed", port.read(committedXid).orElseThrow().status());
            }
        }
    }

    @Test
    void persistedCursorReachesLaterAttemptAfterRestartAndFinalFailureCannotRelease() {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 25; i++) ids.add(attempt("TC-PAGE", "page-" + i, true));
        ids.sort(String::compareTo);
        String last = ids.getLast();
        String xid = jdbc.queryForObject("SELECT xid FROM allocation_attempt WHERE id=?", String.class, last);
        TcStatusPort port = queried -> queried.equals(xid) ? Optional.of(evidence(xid)) : Optional.empty();
        jdbc.update("DELETE FROM allocation_tc_binding WHERE attempt_id=?", ids.getFirst());
        assertEquals("TC_BINDING_MISSING", assertThrows(FulfillmentException.class,
                () -> new AllocationRecoverySweep(sessions, port, SCOPE, CLOCK).execute("TC-PAGE")).code());
        assertEquals(ids.get(19), jdbc.queryForObject("SELECT last_attempt_id FROM allocation_recovery_cursor WHERE enterprise_id='TC-PAGE'", String.class));
        assertEquals("TCC_TRYING", state(last));
        // 最后提交失败时观察、状态、Outbox和本项游标都回滚；不得丢失可恢复项。
        jdbc.execute("ALTER TABLE fulfillment_outbox ADD CONSTRAINT ck_test_no_release CHECK (enterprise_id <> 'TC-PAGE')");
        assertThrows(RuntimeException.class, () -> new AllocationRecoverySweep(sessions, port, SCOPE, CLOCK).execute("TC-PAGE"));
        assertEquals("TCC_TRYING", state(last));
        assertEquals(0, countOutbox(last));
        assertNull(jdbc.queryForObject("SELECT tc_terminal_evidence FROM allocation_attempt WHERE id=?", String.class, last));
        jdbc.execute("ALTER TABLE fulfillment_outbox DROP CHECK ck_test_no_release");
        assertEquals(1, new AllocationRecoverySweep(sessions, port, SCOPE, CLOCK).execute("TC-PAGE").recovered());
        assertEquals(3, countOutbox(last));
    }

    @Test
    void staleObservationAndUnboundConfirmationAndEvidenceRewriteAreRejected() {
        String id = attempt("TC-STALE", "stale-xid", false);
        try (var session = sessions.openSession(false)) {
            var service = new FulfillmentService(session, CLOCK);
            assertEquals("PARTICIPANT_EVIDENCE_MISSING", assertThrows(FulfillmentException.class,
                    () -> service.observeParticipant("TC-STALE", id, "WH-A", "CONFIRMED", 1L)).code());
            service.bindParticipant("TC-STALE", id, "WH-A", "stale-xid", 11, "reserve-A", "res-A", 1, "TRIED");
            assertEquals("BRANCH_ALREADY_BOUND", assertThrows(FulfillmentException.class,
                    () -> service.bindParticipant("TC-STALE", id, "WH-A", "stale-xid", 11, "reserve-A", "other-res", 1, "TRIED")).code());
            service.observeParticipant("TC-STALE", id, "WH-A", "CONFIRMED", 1L);
            service.observeParticipant("TC-STALE", id, "WH-A", "CONFIRMED", 1L);
            assertEquals("PARTICIPANT_EVIDENCE_CONFLICT", assertThrows(FulfillmentException.class,
                    () -> service.observeParticipant("TC-STALE", id, "WH-A", "TRIED", null)).code());
            assertEquals("INVALID_TC_EVIDENCE", assertThrows(FulfillmentException.class,
                    () -> service.observeTc("TC-STALE", id, "Committed", evidence("wrong-xid").evidence())).code());
            session.commit();
        }
        TcStatusPort slow = xid -> {
            // 若TC查询仍持有业务行锁，这个独立连接会超时；同时模拟旧执行器代际已改变。
            jdbc.update("UPDATE allocation_attempt SET launch_epoch=launch_epoch+1 WHERE id=?", id);
            return Optional.of(evidence(xid));
        };
        assertEquals("TC_OBSERVATION_STALE", assertThrows(FulfillmentException.class,
                () -> new AllocationRecoverySweep(sessions, slow, SCOPE, CLOCK).execute("TC-STALE")).code());
        assertEquals(0, countOutbox(id));
        try (var session = sessions.openSession(false)) {
            var service = new FulfillmentService(session, CLOCK);
            service.observeTc("TC-STALE", id, "Committed", evidence("stale-xid").evidence());
            service.observeTc("TC-STALE", id, "Committed", evidence("stale-xid").evidence());
            assertEquals("TC_EVIDENCE_CONFLICT", assertThrows(FulfillmentException.class,
                    () -> service.observeTc("TC-STALE", id, "Rollbacked", "{\"xid\":\"stale-xid\",\"status\":11}")).code());
            assertThrows(FulfillmentException.class, () -> service.observeTc("TC-STALE", id, "Finished", null));
            session.commit();
        }
        assertThrows(IllegalStateException.class, () -> new AllocationRecoveryJob((AllocationRecoverySweep) null, "TC-STALE").execute());
    }

    @Test
    void tcSourceCannotBeReboundAndBarrierReplayCannotIgnoreDifferentEvent() {
        String id = attempt("TC-BIND", "bound-once", true);
        assertEquals("TC_BINDING_CONFLICT", assertThrows(FulfillmentException.class,
                () -> attempt("OTHER-ENTERPRISE", "bound-once", true)).code());
        try (var session = sessions.openSession(false)) {
            var service = new FulfillmentService(session, CLOCK);
            assertEquals("TC_BINDING_CONFLICT", assertThrows(FulfillmentException.class,
                    () -> service.bindXid("TC-BIND", id, "tm", "bound-once",
                            new TcEvidenceScope("other-cluster", "wms-fulfillment", "wms_recovery_group"))).code());
        }
        try (var session = sessions.openSession(false)) {
            var service = new FulfillmentService(session, CLOCK);
            service.observeTc("TC-BIND", id, "Committed", evidence("bound-once").evidence());
            service.markAllocated("TC-BIND", id);
            session.commit();
        }
        assertEquals(3, countOutbox(id));
        var payload = jdbc.queryForObject("SELECT payload FROM fulfillment_outbox WHERE attempt_id=? AND event_type='OutboundOrderRequested'", String.class, id);
        assertEquals("SKU\n编码", tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload)
                .path("lines").get(0).path("skuId").asString());
        jdbc.update("UPDATE fulfillment_outbox SET payload=JSON_OBJECT('conflict',true) WHERE attempt_id=? AND event_type='OutboundOrderRequested'", id);
        try (var session = sessions.openSession(false)) {
            assertEquals("BARRIER_OUTBOX_CONFLICT", assertThrows(FulfillmentException.class,
                    () -> new FulfillmentService(session, CLOCK).markAllocated("TC-BIND", id)).code());
        }
        assertEquals(3, countOutbox(id));
        assertEquals("{\"conflict\": true}", jdbc.queryForObject("SELECT payload FROM fulfillment_outbox WHERE attempt_id=? AND event_type='OutboundOrderRequested'", String.class, id));
    }

    private static String attempt(String enterprise, String xid, boolean confirm) {
        try (var session = sessions.openSession(false)) {
            var service = new FulfillmentService(session, CLOCK);
            String order = String.valueOf(service.createOrder(enterprise, "OMS", UUID.randomUUID().toString(), "a".repeat(64),
                    List.of(Map.of("sourceLineId", "L1", "skuId", "SKU\n编码", "requestedQty", BigDecimal.ONE, "baseUnit", "EA")), 1).get("id"));
            String id = String.valueOf(service.createAttempt(enterprise, order, CLOCK.instant().plusSeconds(60), List.of("WH-A"),
                    List.of(Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU\n编码", "qty", BigDecimal.ONE, "baseUnit", "EA"))).get("id"));
            service.claimLaunch(enterprise, id, "tm");
            service.bindXid(enterprise, id, "tm", xid, SCOPE);
            if (confirm) {
                service.bindParticipant(enterprise, id, "WH-A", xid, 11, "reserve-A", "res-A", 1, "TRIED");
                service.observeParticipant(enterprise, id, "WH-A", "CONFIRMED", 1L);
            }
            session.commit();
            return id;
        }
    }
    private static TcStatusPort.Observation evidence(String xid) {
        return new TcStatusPort.Observation("Committed", com.lrj.wms.runtime.messaging.RuntimeMessage.JSON.writeValueAsString(Map.of(
                "xid",xid,"status",9,"clusterId",SCOPE.clusterId(),"applicationId",SCOPE.applicationId(),"transactionGroup",SCOPE.transactionGroup())));
    }
    private static String state(String id) { return jdbc.queryForObject("SELECT state FROM allocation_attempt WHERE id=?", String.class, id); }
    private static int countOutbox(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM fulfillment_outbox WHERE attempt_id=? AND event_type<>'TcTerminalNoticeV1'", Integer.class, id); }
    private static MysqlDataSource source(MySQLContainer mysql, String user) {
        var ds = new MysqlDataSource();
        ds.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        ds.setUser(user); ds.setPassword(mysql.getPassword()); return ds;
    }
    private static SqlSessionFactory factory(javax.sql.DataSource ds, Class<?>... mappers) {
        var config = new Configuration(new Environment("audit-test", new JdbcTransactionFactory(), ds));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.setDefaultStatementTimeout(2);
        for (var mapper : mappers) config.addMapper(mapper);
        return new SqlSessionFactoryBuilder().build(config);
    }
    /** 选取通常的临时客户端端口段之外的空闲端口，减少Docker启动时出站连接抢占。 */
    private static int freeTcPort() throws java.io.IOException {
        for (int attempt = 0; attempt < 50; attempt++) {
            int candidate = java.util.concurrent.ThreadLocalRandom.current().nextInt(20000, 32000);
            try (var socket = new ServerSocket(candidate, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                return socket.getLocalPort();
            } catch (java.net.BindException occupied) { /* 不结束或重绑已有进程，换下一个随机候选。 */ }
        }
        throw new java.io.IOException("隔离TC找不到空闲测试端口");
    }
    private static void awaitTerminal(JdbcTcStatusPort port, String xid, String status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            if (port.read(xid).map(value -> value.status().equals(status)).orElse(false)) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("TC终态证据未在期限内持久化");
    }
}
