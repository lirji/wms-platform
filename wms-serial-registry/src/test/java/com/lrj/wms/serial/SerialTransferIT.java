package com.lrj.wms.serial;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** S6-02：登记转移状态机、旧 epoch 覆盖拒绝、目的重复收货重放。 */
class SerialTransferIT {
    private static final Instant NOW = Instant.parse("2026-09-12T02:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/registry").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("registry", new JdbcTransactionFactory(), source));
        config.addMapper(SerialRegistryMapper.class);
        config.addMapper(SerialTransferMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void transferStatesRejectStaleEpochAndReplayDestination() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SerialRegistryService service = new SerialRegistryService(session, clock);
            service.claim("ENT-1", "SKU-S", " sn-tr ", "WH-A", "OP-CLAIM");
            assertEquals(SerialRegistryService.STATE_ACTIVE,
                    service.activate("ENT-1", "SKU-S", "sn-tr", "WH-A", "OP-CLAIM").get("state"));
            SerialRegistryException stalePrepare = assertThrows(SerialRegistryException.class,
                    () -> service.prepareTransfer("ENT-1", "SKU-S", "sn-tr", "WH-A", "WH-B", "TR-1", 0, "OP-PREP"));
            assertEquals("STALE_EPOCH", stalePrepare.code());
            Map<String, Object> prepared = service.prepareTransfer("ENT-1", "SKU-S", "sn-tr", "WH-A", "WH-B", "TR-1", 1,
                    "OP-PREP");
            assertEquals(SerialRegistryService.STATE_TRANSFER_PREPARED, prepared.get("state"));
            assertEquals("TR-1", prepared.get("transferId"));
            assertEquals(1L, ((Number) prepared.get("ownerEpoch")).longValue());
            assertEquals(SerialRegistryService.STATE_TRANSFER_PREPARED,
                    service.prepareTransfer("ENT-1", "SKU-S", "SN-TR", "WH-A", "WH-B", "TR-1", 1, "OP-PREP").get("state"));
            SerialRegistryException otherPrep = assertThrows(SerialRegistryException.class,
                    () -> service.prepareTransfer("ENT-1", "SKU-S", "sn-tr", "WH-A", "WH-B", "TR-1", 1, "OP-PREP-2"));
            assertEquals("SERIAL_OPERATION_MISMATCH", otherPrep.code());
            SerialRegistryException earlyDest = assertThrows(SerialRegistryException.class,
                    () -> service.startReceiving("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-1", 1));
            assertEquals("SERIAL_STATE_CONFLICT", earlyDest.code());
            assertEquals(SerialRegistryService.STATE_IN_TRANSIT,
                    service.observeSourceRelease("ENT-1", "SKU-S", "sn-tr", "TR-1", "REL-1", 1).get("state"));
            assertEquals(SerialRegistryService.STATE_IN_TRANSIT,
                    service.observeSourceRelease("ENT-1", "SKU-S", "sn-tr", "TR-1", "REL-1", 1).get("state"));
            SerialRegistryException otherRelease = assertThrows(SerialRegistryException.class,
                    () -> service.observeSourceRelease("ENT-1", "SKU-S", "sn-tr", "TR-1", "REL-2", 1));
            assertEquals("SERIAL_OPERATION_MISMATCH", otherRelease.code());
            assertEquals(SerialRegistryService.STATE_RECEIVING,
                    service.startReceiving("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-1", 1).get("state"));
            assertEquals(SerialRegistryService.STATE_RECEIVING,
                    service.startReceiving("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-1", 1).get("state"));
            Map<String, Object> active = service.confirmDestination("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-1");
            assertEquals(SerialRegistryService.STATE_ACTIVE, active.get("state"));
            assertEquals("WH-B", active.get("ownerWarehouseId"));
            assertEquals(2L, ((Number) active.get("ownerEpoch")).longValue());
            assertEquals("RCV-1", active.get("receiptOperationId"));
            assertEquals(SerialRegistryService.STATE_ACTIVE,
                    service.confirmDestination("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-1").get("state"));
            SerialRegistryException staleObserve = assertThrows(SerialRegistryException.class,
                    () -> service.observeSourceRelease("ENT-1", "SKU-S", "sn-tr", "TR-1", "REL-OLD", 1));
            assertEquals("STALE_EPOCH", staleObserve.code());
            SerialRegistryException staleStart = assertThrows(SerialRegistryException.class,
                    () -> service.startReceiving("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-OLD", 1));
            assertEquals("STALE_EPOCH", staleStart.code());
            SerialRegistryException staleConfirm = assertThrows(SerialRegistryException.class,
                    () -> service.confirmDestination("ENT-1", "SKU-S", "sn-tr", "TR-2", "WH-B", "RCV-2"));
            assertEquals("SERIAL_TRANSFER_NOT_FOUND", staleConfirm.code());
            SerialRegistryException otherDest = assertThrows(SerialRegistryException.class,
                    () -> service.confirmDestination("ENT-1", "SKU-S", "sn-tr", "TR-1", "WH-B", "RCV-2"));
            assertEquals("SERIAL_OPERATION_MISMATCH", otherDest.code());
            session.commit();
        }
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM serial_registry WHERE enterprise_id='ENT-1' AND sku_id='SKU-S' "
                        + "AND normalized_serial='SN-TR'", String.class));
        assertEquals("WH-B", jdbc.queryForObject(
                "SELECT owner_warehouse_id FROM serial_registry WHERE normalized_serial='SN-TR'", String.class));
        assertEquals(2L, jdbc.queryForObject("SELECT owner_epoch FROM serial_registry WHERE normalized_serial='SN-TR'",
                Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_transfer WHERE normalized_serial='SN-TR' AND transfer_id='TR-1'",
                Integer.class));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT state FROM serial_transfer WHERE normalized_serial='SN-TR' AND transfer_id='TR-1'",
                String.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT to_epoch FROM serial_transfer WHERE normalized_serial='SN-TR' AND transfer_id='TR-1'",
                Long.class));
        try (SqlSession session = sessions.openSession(false)) {
            SerialRegistryService service = new SerialRegistryService(session, clock);
            SerialRegistryException staleAfter = assertThrows(SerialRegistryException.class,
                    () -> service.prepareTransfer("ENT-1", "SKU-S", "sn-tr", "WH-A", "WH-B", "TR-1", 1, "OP-PREP"));
            assertEquals("STALE_EPOCH", staleAfter.code());
            assertEquals("WH-B", service.get("ENT-1", "SKU-S", "sn-tr").get("ownerWarehouseId"));
            assertEquals("COMPLETED", service.getTransfer("ENT-1", "SKU-S", "sn-tr", "TR-1").get("state"));
            session.commit();
        }
    }
}
