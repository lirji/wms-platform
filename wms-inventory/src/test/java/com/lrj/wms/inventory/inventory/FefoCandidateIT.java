package com.lrj.wms.inventory.inventory;

import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.domain.Quantity;
import com.lrj.wms.inventory.inventory.domain.StockBucketKey;
import com.lrj.wms.inventory.inventory.infrastructure.CommandDedupMapper;
import com.lrj.wms.inventory.inventory.infrastructure.FefoCandidateMapper;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.inventory.infrastructure.OutboxMapper;
import com.lrj.wms.inventory.masterdata.MasterdataService;
import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import com.lrj.wms.inventory.masterdata.domain.SkuPolicy;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

/** S3-04：FEFO 排除过期批次；预占实时校验效期；上架拒绝发运位。 */
class FefoCandidateIT {
    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final String DIGEST = "d".repeat(64);

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
        config.addMapper(FefoCandidateMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SkuPolicy sku = SkuPolicy.create("SKU-FEFO", "ENT-1", "SKU-FEFO", "效期商品", "EA", 0, true, false, true, 1,
                MasterdataCodes.STATE_ACTIVE);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService masterdata = new MasterdataService(session, clock);
            masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
            masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE", new BigDecimal("100"),
                    "EA");
            masterdata.createLocation("LOC-SHP", "GATE-SHP", "ENT-1", "WH-A", "SHP", "OUT", "SHIPPING", null, null);
            masterdata.createSku(sku, "UNIT-FEFO");
            masterdata.createLot(sku, "LOT-EXP", "WH-A", "OWNER-1", "EXP", "ENT-1/OWNER-1/SKU-FEFO/EXP",
                    Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-10T10:00:00Z"), "2026-09-10", 1);
            masterdata.createLot(sku, "LOT-NEAR", "WH-A", "OWNER-1", "NEAR", "ENT-1/OWNER-1/SKU-FEFO/NEAR",
                    Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-12T10:00:00Z"), "2026-09-12", 1);
            masterdata.createLot(sku, "LOT-FAR", "WH-A", "OWNER-1", "FAR", "ENT-1/OWNER-1/SKU-FEFO/FAR",
                    Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-10-01T10:00:00Z"), "2026-10-01", 1);
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            Quantity five = Quantity.parse("5", 0);
            inventory.receive("ENT-1", "WH-A", "OP-EXP", "DOC", "ACTOR", bucket("LOT-EXP"), five);
            inventory.receive("ENT-1", "WH-A", "OP-NEAR", "DOC", "ACTOR", bucket("LOT-NEAR"), five);
            inventory.receive("ENT-1", "WH-A", "OP-FAR", "DOC", "ACTOR", bucket("LOT-FAR"), five);
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
    void fefoSkipsExpiredAndReserveRejectsExpiredLot() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            List<Map<String, Object>> candidates = new FefoCandidateService(session, clock).list("ENT-1", "WH-A",
                    "OWNER-1", "SKU-FEFO", InventoryCodes.ALLOC_FEFO, 16);
            assertEquals(List.of("LOT-NEAR", "LOT-FAR"),
                    candidates.stream().map(row -> String.valueOf(row.get("lotId"))).toList());
            assertEquals("5", candidates.get(0).get("availableQty"));
            InventoryException fifo = assertThrows(InventoryException.class,
                    () -> new FefoCandidateService(session, clock).list("ENT-1", "WH-A", "OWNER-1", "SKU-FEFO",
                            InventoryCodes.ALLOC_FIFO, 16));
            assertEquals("INVALID_ALLOCATION_POLICY", fifo.code());
            InventoryException expired = assertThrows(InventoryException.class,
                    () -> new InventoryApplicationService(session, clock).reserve("ENT-1", "WH-A", "OP-RSV-EXP", "DOC",
                            "ACTOR", "ALLOC-EXP", "ATT-EXP", "xid-exp", 1L, "ReservationTccAction", 1L, DIGEST,
                            bucket("LOT-EXP"), Quantity.parse("1", 0), "OL-EXP"));
            assertEquals("LOT_EXPIRED", expired.code());
            InventoryException shipping = assertThrows(InventoryException.class,
                    () -> new PutawayTargetService(session).requireStorage("ENT-1", "WH-A", "LOC-SHP"));
            assertEquals("INVALID_PUTAWAY_LOCATION", shipping.code());
            assertEquals("STORAGE", new PutawayTargetService(session).requireStorage("ENT-1", "WH-A", "LOC-1")
                    .get("location_type"));
            session.rollback();
        }
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE lot_id='LOT-EXP'",
                BigDecimal.class).compareTo(BigDecimal.ZERO));
        assertEquals(0, jdbc.queryForObject("SELECT on_hand_qty FROM stock_balance WHERE lot_id='LOT-NEAR'",
                BigDecimal.class).compareTo(new BigDecimal("5.000000")));
    }

    private static StockBucketKey bucket(String lotId) {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-FEFO", lotId, InventoryCodes.QUALITY_GOOD);
    }
}
