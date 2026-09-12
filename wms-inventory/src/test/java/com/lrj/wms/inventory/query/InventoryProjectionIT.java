package com.lrj.wms.inventory.query;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S7-03：eventId 去重、版本顺序、乱序不覆盖、重建追平后切换。 */
class InventoryProjectionIT {
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        Configuration config = new Configuration(new Environment("query", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(OutboxMapper.class);
        config.addMapper(CommandDedupMapper.class);
        config.addMapper(ProjectionMapper.class);
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
    void versionOrderGapAndRebuildSwitch() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-RCV", "DOC", "ACTOR", bucket(), Quantity.parse("5", 0));
            String balanceId = String.valueOf(session.getMapper(InventoryMapper.class)
                    .lockBalanceByDimension("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-Q", MasterdataCodes.NO_LOT,
                            InventoryCodes.QUALITY_GOOD).get("id"));
            InventoryProjectionService views = new InventoryProjectionService(session, clock);
            Timestamp occurred = Timestamp.from(NOW);
            Map<String, Object> late = views.apply("ENT-1", "WH-A", "EVT-2", balanceId, 2,
                    InventoryCodes.EVENT_BALANCE_CHANGED, payload("5", "0"), occurred, "OWNER-1", "LOC-1", "SKU-Q",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            assertEquals(Boolean.FALSE, late.get("applied"));
            Map<String, Object> first = views.apply("ENT-1", "WH-A", "EVT-1", balanceId, 1,
                    InventoryCodes.EVENT_BALANCE_CHANGED, payload("5", "0"), occurred, "OWNER-1", "LOC-1", "SKU-Q",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            assertEquals(Boolean.TRUE, first.get("applied"));
            Map<String, Object> replay = views.apply("ENT-1", "WH-A", "EVT-1", balanceId, 1,
                    InventoryCodes.EVENT_BALANCE_CHANGED, payload("5", "0"), occurred, "OWNER-1", "LOC-1", "SKU-Q",
                    MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
            assertEquals(Boolean.TRUE, replay.get("replayed"));
            Map<String, Object> page = views.query("ENT-1", "WH-A", "SKU-Q", 20);
            assertEquals(1, ((java.util.List<?>) page.get("items")).size());
            assertEquals("2026-09-12T08:00:00Z", page.get("asOf"));
            assertEquals(0L, page.get("lagSeconds"));
            inventory.receive("ENT-1", "WH-A", "OP-RCV-2", "DOC", "ACTOR", bucket(), Quantity.parse("3", 0));
            Map<String, Object> rebuilt = views.rebuild("ENT-1", "WH-A");
            assertEquals(Boolean.TRUE, rebuilt.get("switched"));
            Map<String, Object> after = views.query("ENT-1", "WH-A", "SKU-Q", 20);
            @SuppressWarnings("unchecked")
            Map<String, Object> item = ((java.util.List<Map<String, Object>>) after.get("items")).getFirst();
            assertEquals(0, new BigDecimal(String.valueOf(item.get("on_hand_qty"))).compareTo(new BigDecimal("8")));
            assertEquals(1L, after.get("generation"));
            session.rollback();
        }
    }

    @Test
    void rebuildRefusesWhenShadowLagsLive() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            InventoryProjectionService views = new InventoryProjectionService(session, clock);
            Timestamp occurred = Timestamp.from(NOW);
            views.apply("ENT-1", "WH-A", "EVT-LIVE-1", "BAL-LIVE", 1, InventoryCodes.EVENT_BALANCE_CHANGED,
                    payload("1", "0"), occurred, "OWNER-1", "LOC-1", "SKU-Q", MasterdataCodes.NO_LOT,
                    InventoryCodes.QUALITY_GOOD);
            views.apply("ENT-1", "WH-A", "EVT-LIVE-2", "BAL-LIVE", 2, InventoryCodes.EVENT_BALANCE_CHANGED,
                    payload("2", "0"), occurred, "OWNER-1", "LOC-1", "SKU-Q", MasterdataCodes.NO_LOT,
                    InventoryCodes.QUALITY_GOOD);
            JobRunException lag = assertThrows(JobRunException.class, () -> views.rebuild("ENT-1", "WH-A"));
            assertEquals("REBUILD_LAG", lag.code());
            session.rollback();
        }
    }

    private static StockBucketKey bucket() {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-Q", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static String payload(String onHand, String reserved) {
        return "{\"onHandAfter\":\"" + onHand + "\",\"reservedAfter\":\"" + reserved + "\"}";
    }
}
