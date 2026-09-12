package com.lrj.wms.inventory.serial;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
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

/** S3-03：HOLD 收货、登记激活放行、登记不可用保留意向。ACTIVE 不改变质量。 */
class SerialReceiptIT {
    private static final Instant NOW = Instant.parse("2026-09-11T09:10:00Z");
    private static final String DIGEST = "c".repeat(64);

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
    void holdReceiveActivatesWithoutChangingQuality() {
        MemoryRegistry registry = new MemoryRegistry();
        StockBucketKey hold = bucket("SKU-SN", InventoryCodes.QUALITY_HOLD);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService service = new SerialReceiptService(session, clock, registry);
            Map<String, Object> first = service.receiveHold("ENT-1", "WH-A", "OP-SN-1", "DOC-SN", "ACTOR", " sn-1 ",
                    hold);
            Map<String, Object> replay = service.receiveHold("ENT-1", "WH-A", "OP-SN-1", "DOC-SN", "ACTOR", "SN-1",
                    hold);
            assertEquals(SerialReceiptService.STATE_AUTHORIZED, first.get("state"));
            assertEquals(SerialReceiptService.STATE_AUTHORIZED, replay.get("state"));
            assertEquals("ACTIVE", first.get("registryState"));
            assertNull(first.get("registryError"));
            InventoryException insufficient = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, clock).reserve("ENT-1", "WH-A", "OP-SN-RSV",
                            "DOC-SN", "ACTOR", "ALLOC-SN", "ATT-SN", "xid-sn", 1L, "ReservationTccAction", 1L, DIGEST,
                            bucket("SKU-SN", InventoryCodes.QUALITY_GOOD), Quantity.parse("1", 0), "OL-SN"));
            assertEquals("STOCK_INSUFFICIENT", insufficient.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-SN' AND quality_code='HOLD'", BigDecimal.class)
                .compareTo(new BigDecimal("1.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-SN-1'",
                Integer.class));
        assertEquals("HOLD", jdbc.queryForObject(
                "SELECT quality_code FROM stock_balance WHERE sku_id='SKU-SN' AND quality_code='HOLD'", String.class));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-A' AND serial_id='SN-1'", String.class));
        assertEquals("ACTIVE", registry.row("ENT-1", "SKU-SN", "SN-1").get("state"));
    }

    @Test
    void registryDownKeepsHoldIntentAndRecoverActivates() {
        MemoryRegistry registry = new MemoryRegistry();
        registry.available = false;
        StockBucketKey hold = bucket("SKU-DN", InventoryCodes.QUALITY_HOLD);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService service = new SerialReceiptService(session, clock, registry);
            Map<String, Object> down = service.receiveHold("ENT-1", "WH-A", "OP-DN-1", "DOC-DN", "ACTOR", "sn-down",
                    hold);
            assertEquals(SerialReceiptService.STATE_EXCEPTION, down.get("state"));
            assertEquals(SerialReceiptService.REGISTRY_UNAVAILABLE, down.get("registryError"));
            assertEquals(SerialReceiptService.STATE_EXCEPTION, service.get("ENT-1", "WH-A", " sn-down ").get("state"));
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-DN' AND quality_code='HOLD'", BigDecimal.class)
                .compareTo(new BigDecimal("1.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE serial_id='SN-DOWN'", Integer.class));
        registry.available = true;
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService service = new SerialReceiptService(session, clock, registry);
            Map<String, Object> recovered = service.recover("ENT-1", "WH-A", "sn-down");
            assertEquals(SerialReceiptService.STATE_AUTHORIZED, recovered.get("state"));
            assertEquals("ACTIVE", recovered.get("registryState"));
            assertNull(recovered.get("registryError"));
            session.commit();
        }
        assertEquals("HOLD", jdbc.queryForObject(
                "SELECT quality_code FROM stock_balance WHERE sku_id='SKU-DN' AND quality_code='HOLD'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-DN' AND quality_code='HOLD'", BigDecimal.class)
                .compareTo(new BigDecimal("1.000000")));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE serial_id='SN-DOWN'", String.class));
    }

    @Test
    void duplicateSerialInSameWarehouseIsRejected() {
        MemoryRegistry registry = new MemoryRegistry();
        StockBucketKey hold = bucket("SKU-DUP", InventoryCodes.QUALITY_HOLD);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService service = new SerialReceiptService(session, clock, registry);
            assertEquals(SerialReceiptService.STATE_AUTHORIZED,
                    service.receiveHold("ENT-1", "WH-A", "OP-DUP-1", "DOC-DUP", "ACTOR", "sn-dup", hold).get("state"));
            InventoryException duplicate = assertThrows(InventoryException.class,
                    () -> service.receiveHold("ENT-1", "WH-A", "OP-DUP-2", "DOC-DUP", "ACTOR", "sn-dup", hold));
            assertEquals("SERIAL_ALREADY_RECEIVED", duplicate.code());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE serial_id='SN-DUP'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-DUP-1'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-DUP-2'",
                Integer.class));
    }

    @Test
    void twoWarehousesConcurrentRegisterOnlyOneAuthorizes() throws Exception {
        MemoryRegistry registry = new MemoryRegistry();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger authorized = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        Thread a = new Thread(() -> receiveWarehouse("WH-A", "LOC-S", "OP-TW-A", registry, clock, start, done,
                authorized, conflicted));
        Thread b = new Thread(() -> receiveWarehouse("WH-B", "LOC-B", "OP-TW-B", registry, clock, start, done,
                authorized, conflicted));
        a.start();
        b.start();
        done.await();
        assertEquals(1, authorized.get());
        assertEquals(1, conflicted.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM local_serial WHERE serial_id='SN-TW'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_serial WHERE serial_id='SN-TW' AND state='AUTHORIZED'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_serial WHERE serial_id='SN-TW' AND state='EXCEPTION' "
                        + "AND registry_error='SERIAL_ALREADY_CLAIMED'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_balance WHERE sku_id='SKU-TW' AND quality_code='HOLD' AND on_hand_qty=1",
                Integer.class));
    }

    private void receiveWarehouse(String warehouseId, String locationId, String operation, MemoryRegistry registry,
            Clock clock, CyclicBarrier start, CountDownLatch done, AtomicInteger authorized, AtomicInteger conflicted) {
        try {
            start.await();
            try (SqlSession session = sessions.openSession(false)) {
                StockBucketKey hold = StockBucketKey.of("ENT-1", warehouseId, "OWNER-1", locationId, "SKU-TW",
                        MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_HOLD);
                Map<String, Object> result = new SerialReceiptService(session, clock, registry).receiveHold("ENT-1",
                        warehouseId, operation, "DOC-TW", "ACTOR", "sn-tw", hold);
                session.commit();
                if (SerialReceiptService.STATE_AUTHORIZED.equals(result.get("state"))) {
                    authorized.incrementAndGet();
                } else {
                    assertEquals(SerialReceiptService.STATE_EXCEPTION, result.get("state"));
                    assertEquals("SERIAL_ALREADY_CLAIMED", result.get("registryError"));
                    conflicted.incrementAndGet();
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        } finally {
            done.countDown();
        }
    }

    private static StockBucketKey bucket(String sku, String quality) {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-S", sku, MasterdataCodes.NO_LOT, quality);
    }

    static final class MemoryRegistry implements SerialRegistryPort {
        private final Map<String, Map<String, Object>> rows = new ConcurrentHashMap<>();
        volatile boolean available = true;

        @Override
        public synchronized Map<String, Object> claim(String enterpriseId, String skuId, String serial, String warehouseId,
                String operationId) {
            requireAvailable();
            String key = key(enterpriseId, skuId, serial);
            Map<String, Object> existing = rows.get(key);
            if (existing != null) {
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
        public synchronized Map<String, Object> activate(String enterpriseId, String skuId, String serial, String warehouseId,
                String operationId) {
            requireAvailable();
            Map<String, Object> row = rows.get(key(enterpriseId, skuId, serial));
            if (row == null) {
                throw new SerialRegistryConflictException("SERIAL_NOT_FOUND", "序列号尚未认领");
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
