package com.lrj.wms.outbound.order;

import com.lrj.wms.integration.wcs.SimulatorWcsAdapter;
import com.lrj.wms.outbound.protocol.SourceMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S5-03：共享动作身份、换主沿用、旧worker不能新派工、UNKNOWN 拒派发。 */
class OutboundDispatchIT {
    private static final Instant NOW = Instant.parse("2026-09-12T08:10:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_outbound")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("dispatch", new JdbcTransactionFactory(), source));
        config.addMapper(SourceMapper.class);
        config.addMapper(OutboundOrderMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void workerTakeoverReusesDeviceCommandAndOldWorkerCannotDispatch() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MemoryAuthorization auth = new MemoryAuthorization();
        SimulatorWcsAdapter wcs = new SimulatorWcsAdapter(clock);
        String taskId;
        String deviceCommandId;
        try (SqlSession session = sessions.openSession(false)) {
            OutboundOrderService orders = new OutboundOrderService(session, clock);
            Map<String, Object> created = orders.createFromAllocation("ENT-1", "WH-A", "ALLOC-D", "ATT-D", "OWNER-1",
                    "AUTH-1", List.of(Map.of("orderLineId", "L1", "skuId", "SKU-1", "qty", new BigDecimal("3"),
                            "baseUnit", "EA")));
            Map<String, Object> planned = orders.planPickTask("ENT-1", "WH-A", String.valueOf(created.get("id")), "L1",
                    "LOC-1", "STG-1", new BigDecimal("3"));
            taskId = String.valueOf(planned.get("taskId"));
            OutboundDispatchService dispatch = new OutboundDispatchService(session, clock, auth, wcs, wcs);
            OutboundException fenced = assertThrows(OutboundException.class,
                    () -> dispatch.dispatch("ENT-1", "WH-A", taskId, "W-OLD", 0L));
            assertEquals("WORKER_FENCED", fenced.code());
            Map<String, Object> first = dispatch.claim("ENT-1", "WH-A", taskId, "W-A");
            deviceCommandId = String.valueOf(first.get("deviceCommandId"));
            Map<String, Object> started = dispatch.dispatch("ENT-1", "WH-A", taskId, "W-A",
                    ((Number) first.get("claimEpoch")).longValue());
            assertEquals("SIMULATOR", started.get("implementation"));
            assertEquals(deviceCommandId, started.get("deviceCommandId"));
            Map<String, Object> takeover = dispatch.claim("ENT-1", "WH-A", taskId, "W-B");
            assertEquals(deviceCommandId, takeover.get("deviceCommandId"));
            OutboundException oldWorker = assertThrows(OutboundException.class,
                    () -> dispatch.dispatch("ENT-1", "WH-A", taskId, "W-A",
                            ((Number) first.get("claimEpoch")).longValue()));
            assertEquals("WORKER_FENCED", oldWorker.code());
            dispatch.dispatch("ENT-1", "WH-A", taskId, "W-B", ((Number) takeover.get("claimEpoch")).longValue());
            dispatch.recoverReceipt("ENT-1", "WH-A", taskId, "EVT-U", "UNKNOWN", BigDecimal.ZERO);
            OutboundException unknown = assertThrows(OutboundException.class,
                    () -> dispatch.dispatch("ENT-1", "WH-A", taskId, "W-B",
                            ((Number) takeover.get("claimEpoch")).longValue()));
            assertEquals("DEVICE_UNKNOWN", unknown.code());
            session.commit();
        }
        assertEquals(deviceCommandId, jdbc.queryForObject(
                "SELECT device_command_id FROM outbound_task WHERE id=?", String.class, taskId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT device_command_id) FROM outbound_task WHERE id=?", Integer.class, taskId));
        System.out.println("S5_DISPATCH: shared action identity; takeover reuses command; old worker fenced");
    }

    private static final class MemoryAuthorization implements ExecutionAuthorizationPort {
        private final Map<String, String> states = new HashMap<>();

        @Override
        public Map<String, Object> startPermit(String enterpriseId, String warehouseId, String commandId, String taskId,
                long taskEpoch, String parentId, String partId, String lineId, BigDecimal qty) {
            String state = states.getOrDefault(commandId, "STARTED");
            states.putIfAbsent(commandId, "STARTED");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commandId", commandId);
            body.put("permitState", state);
            body.put("taskId", taskId);
            body.put("taskEpoch", taskEpoch);
            return body;
        }

        @Override
        public Map<String, Object> markUnknown(String enterpriseId, String warehouseId, String commandId) {
            states.put(commandId, "UNKNOWN");
            return Map.of("commandId", commandId, "permitState", "UNKNOWN");
        }
    }
}
