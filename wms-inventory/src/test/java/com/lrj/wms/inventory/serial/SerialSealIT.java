package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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

/** S6-02：源仓 SEALED 忽略旧授权，目的重复收货重放，登记未在途不放行。 */
class SerialSealIT {
    private static final Instant NOW = Instant.parse("2026-09-12T02:10:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(LocalSerialMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-S", "GATE-S", "ENT-1", "WH-A", "S-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createWarehouse("WH-B", "ENT-1", "NGB", "宁波仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-B", "GATE-B", "ENT-1", "WH-B", "B-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            session.commit();
        }
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void sealedIgnoresOldAuthAndDestinationReplays() {
        SerialReceiptIT.MemoryRegistry claimRegistry = new SerialReceiptIT.MemoryRegistry();
        MemoryTransferRegistry transferRegistry = new MemoryTransferRegistry();
        transferRegistry.blockUntilRelease();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey sourceHold = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-S", "SKU-TR",
                MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_HOLD);
        StockBucketKey destHold = StockBucketKey.of("ENT-1", "WH-B", "OWNER-1", "LOC-B", "SKU-TR",
                MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, claimRegistry);
            SerialTransferLocalService transfers = new SerialTransferLocalService(session, clock, transferRegistry);
            assertEquals(SerialReceiptService.STATE_AUTHORIZED,
                    receipts.receiveHold("ENT-1", "WH-A", "OP-SRC", "DOC-TR", "ACTOR", " sn-tr ", sourceHold).get("state"));
            InventoryException staleSeal = assertThrows(InventoryException.class,
                    () -> transfers.sealSource("ENT-1", "WH-A", "sn-tr", "TR-1", 0, "REL-1"));
            assertEquals("STALE_EPOCH", staleSeal.code());
            Map<String, Object> sealed = transfers.sealSource("ENT-1", "WH-A", "sn-tr", "TR-1", 1, "REL-1");
            assertEquals(SerialTransferLocalService.STATE_SEALED, sealed.get("state"));
            assertEquals("TR-1", sealed.get("transferId"));
            assertEquals(SerialTransferLocalService.STATE_SEALED,
                    transfers.sealSource("ENT-1", "WH-A", "SN-TR", "TR-1", 1, "REL-1").get("state"));
            assertEquals(SerialTransferLocalService.STATE_SEALED,
                    transfers.applyObservedAuthorization("ENT-1", "WH-A", "sn-tr", 1, "ACTIVE").get("state"));
            InventoryException resealed = assertThrows(InventoryException.class,
                    () -> receipts.receiveHold("ENT-1", "WH-A", "OP-SRC", "DOC-TR", "ACTOR", "sn-tr", sourceHold));
            assertEquals("SERIAL_SEALED", resealed.code());
            assertEquals(SerialTransferLocalService.STATE_SEALED, receipts.recover("ENT-1", "WH-A", "sn-tr").get("state"));
            Map<String, Object> blocked = transfers.receiveDestination("ENT-1", "WH-B", "OP-DST", "DOC-TR", "ACTOR",
                    "sn-tr", destHold, "TR-1", 1);
            assertEquals(SerialReceiptService.STATE_HOLD_RECEIVED, blocked.get("state"));
            assertEquals("SERIAL_STATE_CONFLICT", blocked.get("registryError"));
            transferRegistry.releaseToTransit();
            Map<String, Object> dest = transfers.receiveDestination("ENT-1", "WH-B", "OP-DST", "DOC-TR", "ACTOR",
                    "sn-tr", destHold, "TR-1", 1);
            assertEquals(SerialReceiptService.STATE_AUTHORIZED, dest.get("state"));
            assertEquals(2L, ((Number) dest.get("ownerEpoch")).longValue());
            assertEquals("TR-1", dest.get("transferId"));
            assertEquals(SerialReceiptService.STATE_AUTHORIZED,
                    transfers.receiveDestination("ENT-1", "WH-B", "OP-DST", "DOC-TR", "ACTOR", "SN-TR", destHold, "TR-1",
                            1).get("state"));
            InventoryException otherDest = assertThrows(InventoryException.class,
                    () -> transfers.receiveDestination("ENT-1", "WH-B", "OP-DST-2", "DOC-TR", "ACTOR", "sn-tr", destHold,
                            "TR-1", 1));
            assertEquals("SERIAL_ALREADY_RECEIVED", otherDest.code());
            session.commit();
        }
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-A' AND serial_id='SN-TR'", String.class));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-B' AND serial_id='SN-TR'", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE serial_id='SN-TR'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-DST'",
                Integer.class));
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-A' AND serial_id='SN-TR'", String.class));
    }

    static final class MemoryTransferRegistry implements SerialTransferRegistryPort {
        private String state = "TRANSFER_PREPARED";
        private String receiptRef;
        private long fromEpoch = 1L;

        void blockUntilRelease() {
            state = "TRANSFER_PREPARED";
        }

        void releaseToTransit() {
            state = "IN_TRANSIT";
        }

        @Override
        public synchronized Map<String, Object> startReceiving(String enterpriseId, String skuId, String serial,
                String transferId, String targetWarehouseId, String targetReceiptRef, long expectedFromEpoch) {
            if (!"IN_TRANSIT".equals(state) && !"RECEIVING".equals(state) && !"ACTIVE".equals(state)) {
                throw new SerialRegistryConflictException("SERIAL_STATE_CONFLICT", "登记未在途，不能授予目的仓接收");
            }
            if (expectedFromEpoch != fromEpoch && !"ACTIVE".equals(state)) {
                throw new SerialRegistryConflictException("STALE_EPOCH", "旧归属代际事件不能覆盖当前授权");
            }
            if (receiptRef != null && !receiptRef.equals(targetReceiptRef)) {
                throw new SerialRegistryConflictException("SERIAL_OPERATION_MISMATCH", "目的接收引用与已记录不一致");
            }
            receiptRef = targetReceiptRef;
            if (!"ACTIVE".equals(state)) {
                state = "RECEIVING";
            }
            return view(targetWarehouseId);
        }

        @Override
        public synchronized Map<String, Object> confirmDestination(String enterpriseId, String skuId, String serial,
                String transferId, String targetWarehouseId, String targetReceiptRef) {
            if (receiptRef != null && !receiptRef.equals(targetReceiptRef)) {
                throw new SerialRegistryConflictException("SERIAL_OPERATION_MISMATCH", "目的接收引用与已记录不一致");
            }
            if (!"RECEIVING".equals(state) && !"ACTIVE".equals(state)) {
                throw new SerialRegistryConflictException("SERIAL_STATE_CONFLICT", "当前登记状态不能确认目的归属");
            }
            receiptRef = targetReceiptRef;
            state = "ACTIVE";
            Map<String, Object> body = view(targetWarehouseId);
            body.put("ownerEpoch", fromEpoch + 1);
            return body;
        }

        private Map<String, Object> view(String warehouseId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("state", state);
            body.put("ownerWarehouseId", warehouseId);
            body.put("ownerEpoch", "ACTIVE".equals(state) ? fromEpoch + 1 : fromEpoch);
            body.put("transferId", "TR-1");
            body.put("receiptOperationId", receiptRef);
            return body;
        }
    }
}
