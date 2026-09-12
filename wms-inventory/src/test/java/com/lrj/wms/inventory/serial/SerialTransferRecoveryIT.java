package com.lrj.wms.inventory.serial;

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

/** S6-04：源仓已封闭但登记未见释放时目的保持 HOLD，恢复后不二次扣源仓。不是 AC-17 生产。 */
class SerialTransferRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:10:00Z");
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
    void sealedSourceWithoutReleaseKeepsDestinationHoldAndReplayDoesNotRededuct() {
        SerialReceiptIT.MemoryRegistry claims = new SerialReceiptIT.MemoryRegistry();
        SerialSealIT.MemoryTransferRegistry registry = new SerialSealIT.MemoryTransferRegistry();
        registry.blockUntilRelease();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey source = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-S", "SKU-RV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        StockBucketKey dest = StockBucketKey.of("ENT-1", "WH-B", "OWNER-1", "LOC-B", "SKU-RV", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        try (SqlSession session = sessions.openSession(false)) {
            SerialReceiptService receipts = new SerialReceiptService(session, clock, claims);
            SerialTransferLocalService transfers = new SerialTransferLocalService(session, clock, registry);
            receipts.receiveHold("ENT-1", "WH-A", "OP-RV-S", "DOC-RV", "ACTOR", "sn-rv", source);
            assertEquals(SerialTransferLocalService.STATE_SEALED,
                    transfers.sealSource("ENT-1", "WH-A", "sn-rv", "TR-RV", 1, "REL-RV").get("state"));
            Map<String, Object> held = transfers.receiveDestination("ENT-1", "WH-B", "OP-RV-D", "DOC-RV", "ACTOR",
                    "sn-rv", dest, "TR-RV", 1);
            assertEquals(SerialReceiptService.STATE_HOLD_RECEIVED, held.get("state"));
            session.commit();
        }
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-A' AND serial_id='SN-RV'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-RV'", BigDecimal.class)
                .compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-B' AND sku_id='SKU-RV'", BigDecimal.class)
                .compareTo(new BigDecimal("1.000000")));

        registry.releaseToTransit();
        try (SqlSession session = sessions.openSession(false)) {
            SerialTransferLocalService transfers = new SerialTransferLocalService(session, clock, registry);
            assertEquals(SerialReceiptService.STATE_AUTHORIZED,
                    transfers.receiveDestination("ENT-1", "WH-B", "OP-RV-D", "DOC-RV", "ACTOR", "sn-rv", dest, "TR-RV",
                            1).get("state"));
            assertEquals(SerialTransferLocalService.STATE_SEALED,
                    transfers.sealSource("ENT-1", "WH-A", "SN-RV", "TR-RV", 1, "REL-RV").get("state"));
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='REL-RV'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A' AND sku_id='SKU-RV'", BigDecimal.class)
                .compareTo(BigDecimal.ZERO));
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM local_serial WHERE warehouse_id='WH-B' AND serial_id='SN-RV'", String.class));
    }
}
