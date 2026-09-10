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

/** AC-03：并发预占不超过实物。保存最终余额/预占不变量。 */
class InventoryConcurrencyIT {
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
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-C", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-SEED", "DOC", "ACTOR", bucket,
                    Quantity.parse("100", 0));
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
    void concurrentReserveDoesNotOverAllocate() throws Exception {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-C", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        int workers = 20;
        CyclicBarrier start = new CyclicBarrier(workers);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    try (SqlSession session = sessions.openSession(false)) {
                        InventoryApplicationService service = new InventoryApplicationService(session, clock);
                        service.reserve("ENT-1", "WH-A", "OP-C-" + index, "DOC", "ACTOR", "ALLOC-" + index,
                                "ATT-" + index, "xid-" + index, index, "ReservationTccAction", 1L, "d".repeat(64),
                                bucket, Quantity.parse("10", 0), "OL-" + index);
                        session.commit();
                        won.incrementAndGet();
                    }
                } catch (InventoryException error) {
                    assertEquals("STOCK_INSUFFICIENT", error.code());
                    lost.incrementAndGet();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                } finally {
                    done.countDown();
                }
            });
            threads[i].start();
        }
        done.await();
        assertEquals(10, won.get());
        assertEquals(10, lost.get());
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE sku_id='SKU-C'", BigDecimal.class)
                .compareTo(new BigDecimal("100.000000")));
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-C'", BigDecimal.class)
                .compareTo(new BigDecimal("100.000000")));
        BigDecimal claim = jdbc.queryForObject("SELECT free_execution_claim_qty FROM stock_balance WHERE sku_id='SKU-C'",
                BigDecimal.class);
        assertEquals(0, claim.compareTo(BigDecimal.ZERO));
        assertEquals(10, jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE state='TRIED'", Integer.class));
    }
}
