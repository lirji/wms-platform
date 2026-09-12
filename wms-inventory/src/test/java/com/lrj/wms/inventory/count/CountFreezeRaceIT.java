package com.lrj.wms.inventory.count;

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
import com.lrj.wms.inventory.serial.LocalSerialMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
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

/** S6-04：QUIESCING 后冻结与新预占并发。不是 AC-18 生产。 */
class CountFreezeRaceIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:00:00Z");
    private static final String DIGEST = "f".repeat(64);
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
        config.addMapper(CountMapper.class);
        config.addMapper(LocalSerialMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-R", "GATE-R", "ENT-1", "WH-A", "R-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            StockBucketKey good = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-R", "SKU-R", MasterdataCodes.NO_LOT,
                    InventoryCodes.QUALITY_GOOD);
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-R-RCV", "DOC-R", "ACTOR", good,
                    Quantity.parse("10", 0));
            CountService counts = new CountService(session, clock);
            counts.create("ENT-1", "WH-A", "CP-R", "CYCLE", List.of("LOC-R"));
            counts.startQuiescing("ENT-1", "WH-A", "CP-R");
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
    void freezeBeatsNewReserveAfterQuiescing() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        StockBucketKey good = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-R", "SKU-R", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> freezeStatus = new AtomicReference<>();
        AtomicReference<String> reserveCode = new AtomicReference<>();
        Thread freezer = new Thread(() -> {
            try {
                start.await();
                try (SqlSession session = sessions.openSession(false)) {
                    freezeStatus.set(String.valueOf(new CountService(session, clock).freeze("ENT-1", "WH-A", "CP-R")
                            .get("status")));
                    session.commit();
                }
            } catch (Exception error) {
                throw new IllegalStateException(error);
            } finally {
                done.countDown();
            }
        });
        Thread reserver = new Thread(() -> {
            try {
                start.await();
                try (SqlSession session = sessions.openSession(false)) {
                    new InventoryApplicationService(session, clock).reserve("ENT-1", "WH-A", "OP-R-RSV", "DOC-R",
                            "ACTOR", "ALLOC-R", "ATT-R", "xid-r", 1L, "ReservationTccAction", 1L, DIGEST, good,
                            Quantity.parse("1", 0), "OL-R");
                    session.commit();
                    reserveCode.set("COMMITTED");
                }
            } catch (InventoryException error) {
                reserveCode.set(error.code());
            } catch (Exception error) {
                throw new IllegalStateException(error);
            } finally {
                done.countDown();
            }
        });
        freezer.start();
        reserver.start();
        done.await();
        assertEquals(CountService.FROZEN, freezeStatus.get());
        assertEquals("STOCK_FROZEN", reserveCode.get());
        assertEquals("FROZEN", jdbc.queryForObject("SELECT state FROM location_gate WHERE location_id='LOC-R'",
                String.class));
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-R'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-R'",
                BigDecimal.class).compareTo(new BigDecimal("10.000000")));
    }
}
