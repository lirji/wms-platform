package com.lrj.wms.inventory.tcc;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.ReservationState;
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
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seata.core.context.RootContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S4-06：XXL 巡检 TRIED 不得自行释放或二阶段。不是官方 admin 触发或集群。 */
class TccReservationWatchIT {
    private static final Instant NOW = Instant.parse("2026-09-12T04:00:00Z");
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
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("watch", new JdbcTransactionFactory(), source));
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                    new BigDecimal("100"), "EA");
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
    void watchDoesNotReleaseTriedOrDecidePhaseTwo() {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-W",
                MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-RCV-W", "DOC", "ACTOR", bucket, Quantity.parse("10", 0));
            inventory.reserve("ENT-1", "WH-A", "OP-RSV-W", "DOC", "ACTOR", "ALLOC-W", "ATT-W", "xid-watch", 21L,
                    ReservationTccAction.ACTION_NAME, 1L, DIGEST, bucket, Quantity.parse("3", 0), "OL-W");
            session.commit();
        }
        RootContext.bind("xxl-must-clear");
        try (SqlSession session = sessions.openSession(true)) {
            TccReservationWatch.Report report = new TccReservationWatchJob(
                    new TccReservationWatch(session), "ENT-1", "WH-A").execute();
            assertEquals(1, report.tried());
            assertEquals(0, report.confirmed());
            assertThrows(IllegalStateException.class, TccReservationWatch::refusePhaseTwo);
        }
        assertNull(RootContext.getXID());
        assertEquals(ReservationState.TRIED,
                jdbc.queryForObject("SELECT state FROM reservation WHERE allocation_id='ALLOC-W'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-W'",
                BigDecimal.class).compareTo(new BigDecimal("3.000000")));
        System.out.println("S4_XXL_WATCH: TRIED remains reserved; handler cleared XID; no Confirm/Cancel");
    }
}
