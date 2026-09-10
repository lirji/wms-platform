package com.lrj.wms.inventory.inventory;

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

/** AC-04：同键重试一次入账，同键异内容拒绝。 */
class IdempotencyRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-11T05:00:00Z");
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
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
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
    void lostResponseRetryAndConflict() {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-ID", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Quantity qty = Quantity.parse("5", 0);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-LOST", "DOC-1", "ACTOR", bucket,
                    qty);
            session.commit();
        }
        try (SqlSession session = sessions.openSession(false)) {
            assertEquals("OP-LOST", new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-LOST",
                    "DOC-1", "ACTOR", bucket, qty));
            session.commit();
        }
        InventoryException conflict = assertThrows(InventoryException.class, () -> {
            try (SqlSession session = sessions.openSession(false)) {
                new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-LOST", "DOC-OTHER", "ACTOR",
                        bucket, qty);
            }
        });
        assertEquals("COMMAND_CONFLICT", conflict.code());
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-ID'", BigDecimal.class)
                .compareTo(new BigDecimal("5.000000")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-LOST'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM command_dedup WHERE client_operation_id='OP-LOST'",
                Integer.class));
    }
}
