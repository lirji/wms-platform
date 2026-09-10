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

/** AC-05：Outbox 插入失败时余额/流水/占用一起回滚。 */
class OutboxCrashRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-11T05:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource root = new MysqlDataSource();
        root.setUrl(mysql.getJdbcUrl());
        root.setUser("root");
        root.setPassword(mysql.getPassword());
        new JdbcTemplate(root).execute("SET GLOBAL log_bin_trust_function_creators = 1");
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
    void outboxInsertFailureRollsBalanceAndLedger() {
        jdbc.execute("CREATE TRIGGER trg_fail_outbox BEFORE INSERT ON outbox_event FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected outbox failure'");
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-CRASH", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            assertThrows(Exception.class, () -> new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A",
                    "OP-CRASH", "DOC", "ACTOR", bucket, Quantity.parse("7", 0)));
            session.rollback();
        } finally {
            jdbc.execute("DROP TRIGGER trg_fail_outbox");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-CRASH'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE operation_id='OP-CRASH'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_dedup WHERE client_operation_id='OP-CRASH'",
                Integer.class));
        Integer balances = jdbc.queryForObject("SELECT COUNT(*) FROM stock_balance WHERE sku_id='SKU-CRASH'", Integer.class);
        if (balances != 0) {
            assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-CRASH'",
                    BigDecimal.class).compareTo(BigDecimal.ZERO));
        }
    }
}
