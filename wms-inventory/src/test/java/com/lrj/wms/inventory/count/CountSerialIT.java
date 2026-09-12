package com.lrj.wms.inventory.count;

import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.serial.LocalSerialMapper;
import com.lrj.wms.inventory.serial.SerialCountRegistryPort;
import com.lrj.wms.inventory.serial.SerialReceiptService;
import com.lrj.wms.inventory.serial.SerialRegistryConflictException;
import com.lrj.wms.inventory.serial.SerialRegistryPort;
import com.lrj.wms.inventory.serial.SerialRegistryUnavailableException;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

/** S6-03a：序列号观察集合与 FOUND/MISSING。不是 AC-18/19 生产。 */
class CountSerialIT {
    private static final Instant NOW = Instant.parse("2026-09-12T04:10:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(CountMapper.class);
        config.addMapper(LocalSerialMapper.class);
        config.addMapper(com.lrj.wms.inventory.serial.SerialRecoveryMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-SN", "GATE-SN", "ENT-1", "WH-A", "S-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-DN", "GATE-DN", "ENT-1", "WH-A", "S-02", "A", "STORAGE", new BigDecimal("100"),
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
    void identitiesAdjustFoundMissingAndRejectQtyOnly() {
        MemoryCountRegistry registry = new MemoryCountRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey hold = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-SN", "SKU-CS", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, registry);
            CountService counts = new CountService(session, clock, registry);
            receipts.receiveHold("ENT-1", "WH-A", "OP-CS-A", "DOC-CS", "ACTOR", "sn-a", hold);
            receipts.receiveHold("ENT-1", "WH-A", "OP-CS-B", "DOC-CS", "ACTOR", "sn-b", hold);
            counts.create("ENT-1", "WH-A", "CP-SN", "CYCLE", List.of("LOC-SN"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-SN");
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-SN");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            String lineId = String.valueOf(lines.getFirst().get("id"));
            InventoryException qtyOnly = assertThrows(InventoryException.class,
                    () -> counts.observe("ENT-1", "WH-A", "CP-SN", lineId, "OBS-Q", "2", "ACTOR", 1));
            assertEquals("SERIAL_SET_REQUIRED", qtyOnly.code());
            InventoryException size = assertThrows(InventoryException.class,
                    () -> counts.observeIdentities("ENT-1", "WH-A", "CP-SN", lineId, "OBS-S", "3", "ACTOR", 1,
                            List.of("sn-a", "sn-c")));
            assertEquals("SERIAL_QTY_MISMATCH", size.code());
            Map<String, Object> observed = counts.observeIdentities("ENT-1", "WH-A", "CP-SN", lineId, "OBS-1", "2",
                    "ACTOR", 1, List.of("sn-a", "sn-c"));
            assertEquals(1, observed.get("found"));
            assertEquals(1, observed.get("missing"));
            counts.submitReview("ENT-1", "WH-A", "CP-SN");
            counts.approve("ENT-1", "WH-A", "CP-SN", "AP-SN", "APPR");
            assertEquals(CountService.LINE_ZERO,
                    counts.applyLine("ENT-1", "WH-A", "CP-SN", lineId, "OP-CS-ADJ", "ACTOR").get("status"));
            assertEquals(CountService.COMPLETED, counts.unfreeze("ENT-1", "WH-A", "CP-SN").get("status"));
            InventoryException missingRecover = assertThrows(InventoryException.class,
                    () -> receipts.recover("ENT-1", "WH-A", "sn-b"));
            assertEquals("SERIAL_MISSING", missingRecover.code());
            session.commit();
        }
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-A'", String.class));
        assertEquals("MISSING", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-B'", String.class));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-C'", String.class));
        assertEquals("MISSING", registry.row("ENT-1", "SKU-CS", "SN-B").get("state"));
        assertEquals("ACTIVE", registry.row("ENT-1", "SKU-CS", "SN-C").get("state"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-CS'", BigDecimal.class)
                .compareTo(new BigDecimal("2.000000")));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM count_observation_serial WHERE observation_id='OBS-1'", Integer.class));
    }

    @Test
    void registryDownKeepsLineFrozen() {
        MemoryCountRegistry registry = new MemoryCountRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey hold = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-DN", "SKU-DN", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, registry);
            CountService counts = new CountService(session, clock, registry);
            receipts.receiveHold("ENT-1", "WH-A", "OP-DN-X", "DOC-DN", "ACTOR", "sn-x", hold);
            receipts.receiveHold("ENT-1", "WH-A", "OP-DN-Y", "DOC-DN", "ACTOR", "sn-y", hold);
            counts.create("ENT-1", "WH-A", "CP-DN", "CYCLE", List.of("LOC-DN"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-DN");
            Map<String, Object> frozen = counts.freeze("ENT-1", "WH-A", "CP-DN");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) frozen.get("lines");
            String lineId = String.valueOf(lines.getFirst().get("id"));
            counts.observeIdentities("ENT-1", "WH-A", "CP-DN", lineId, "OBS-DN", "1", "ACTOR", 1, List.of("sn-x"));
            counts.submitReview("ENT-1", "WH-A", "CP-DN");
            counts.approve("ENT-1", "WH-A", "CP-DN", "AP-DN", "APPR");
            registry.available = false;
            InventoryException pending = assertThrows(InventoryException.class,
                    () -> counts.applyLine("ENT-1", "WH-A", "CP-DN", lineId, "OP-DN-ADJ", "ACTOR"));
            assertEquals("COUNT_REGISTRY_PENDING", pending.code());
            InventoryException frozenStill = assertThrows(InventoryException.class,
                    () -> counts.unfreeze("ENT-1", "WH-A", "CP-DN"));
            assertTrue("COUNT_APPLY_PENDING".equals(frozenStill.code())
                    || "COUNT_REGISTRY_PENDING".equals(frozenStill.code()));
            session.commit();
        }
        assertEquals("MISSING_PENDING", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-Y'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-DN'", BigDecimal.class)
                .compareTo(new BigDecimal("2.000000")));
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-DN'",
                String.class));
        assertNotEquals("APPLIED", jdbc.queryForObject(
                "SELECT status FROM count_line WHERE count_plan_id='CP-DN'", String.class));
    }

    static final class MemoryCountRegistry implements SerialRegistryPort, SerialCountRegistryPort {
        private final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();
        volatile boolean available = true;

        @Override
        public synchronized Map<String, Object> claim(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            String key = key(enterpriseId, skuId, serial);
            Map<String, Object> existing = rows.get(key);
            if (existing != null) {
                if ("MISSING".equals(existing.get("state"))) {
                    throw new SerialRegistryConflictException("SERIAL_MISSING", "失踪序列号不能按首次认领占用");
                }
                if (!operationId.equals(existing.get("claimOperationId"))) {
                    throw new SerialRegistryConflictException("SERIAL_ALREADY_CLAIMED", "序列号已被其他操作认领");
                }
                return copy(existing);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", UUID.randomUUID().toString());
            row.put("state", "CLAIMED");
            row.put("normalizedSerial", SerialReceiptService.normalize(serial));
            row.put("ownerWarehouseId", warehouseId);
            row.put("ownerEpoch", 1L);
            row.put("claimOperationId", operationId);
            rows.put(key, row);
            return copy(row);
        }

        @Override
        public synchronized Map<String, Object> activate(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未认领");
            }
            if ("MISSING".equals(row.get("state"))) {
                throw new SerialRegistryConflictException("SERIAL_MISSING", "失踪序列号不能按原认领激活");
            }
            if (!warehouseId.equals(row.get("ownerWarehouseId"))) {
                throw new SerialRegistryConflictException("SERIAL_OWNER_MISMATCH", "激活仓与登记归属不一致");
            }
            if (!operationId.equals(row.get("claimOperationId"))) {
                throw new SerialRegistryConflictException("SERIAL_OPERATION_MISMATCH", "激活操作与认领不一致");
            }
            row.put("state", "ACTIVE");
            return copy(row);
        }

        @Override
        public synchronized Map<String, Object> markMissing(String enterpriseId, String skuId, String serial,
                String warehouseId, String factRef, long expectedEpoch) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未登记");
            }
            if ("MISSING".equals(row.get("state")) && factRef.equals(row.get("factRef"))) {
                return copy(row);
            }
            if (expectedEpoch != ((Number) row.get("ownerEpoch")).longValue()) {
                throw new SerialRegistryConflictException("STALE_EPOCH", "失踪事实代际不匹配");
            }
            if (!"ACTIVE".equals(row.get("state")) || !warehouseId.equals(row.get("ownerWarehouseId"))) {
                throw new SerialRegistryConflictException("SERIAL_STATE_CONFLICT", "当前登记状态不能标失踪");
            }
            row.put("state", "MISSING");
            row.put("factRef", factRef);
            return copy(row);
        }

        @Override
        public synchronized Map<String, Object> claimFound(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            Map<String, Object> existing = rows.get(key(enterpriseId, skuId, serial));
            if (existing == null) {
                return claim(enterpriseId, skuId, serial, warehouseId, operationId);
            }
            if ("FOUND_CLAIMED".equals(existing.get("state")) && operationId.equals(existing.get("claimOperationId"))) {
                return copy(existing);
            }
            if ("ACTIVE".equals(existing.get("state"))) {
                throw new SerialRegistryConflictException("SERIAL_ALREADY_CLAIMED", "序列号仍是有效授权，不能盘盈认领");
            }
            if (!"MISSING".equals(existing.get("state"))) {
                throw new SerialRegistryConflictException("SERIAL_STATE_CONFLICT", "当前登记状态不能盘盈认领");
            }
            existing.put("state", "FOUND_CLAIMED");
            existing.put("ownerWarehouseId", warehouseId);
            existing.put("ownerEpoch", ((Number) existing.get("ownerEpoch")).longValue() + 1);
            existing.put("claimOperationId", operationId);
            return copy(existing);
        }

        @Override
        public synchronized Map<String, Object> activateFound(String enterpriseId, String skuId, String serial,
                String warehouseId, String operationId) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未认领");
            }
            if ("ACTIVE".equals(row.get("state")) && warehouseId.equals(row.get("ownerWarehouseId"))) {
                return copy(row);
            }
            if (!"FOUND_CLAIMED".equals(row.get("state"))) {
                return activate(enterpriseId, skuId, serial, warehouseId, operationId);
            }
            if (!operationId.equals(row.get("claimOperationId"))) {
                throw new SerialRegistryConflictException("SERIAL_OPERATION_MISMATCH", "盘盈激活操作与认领不一致");
            }
            row.put("state", "ACTIVE");
            return copy(row);
        }

        @Override
        public Map<String, Object> get(String enterpriseId, String skuId, String serial) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未登记");
            }
            return copy(row);
        }

        Map<String, Object> row(String enterpriseId, String skuId, String serial) {
            return copy(rows.get(key(enterpriseId, skuId, serial)));
        }

        private void requireAvailable() {
            if (!available) {
                throw new SerialRegistryUnavailableException("登记服务不可用");
            }
        }

        private static String key(String enterpriseId, String skuId, String serial) {
            return enterpriseId + '|' + skuId + '|' + SerialReceiptService.normalize(serial);
        }

        private static Map<String, Object> copy(Map<String, Object> row) {
            return row == null ? null : new LinkedHashMap<>(row);
        }
    }
}
