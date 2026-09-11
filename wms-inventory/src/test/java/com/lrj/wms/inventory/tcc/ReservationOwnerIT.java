package com.lrj.wms.inventory.tcc;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
import com.lrj.wms.inventory.inventory.InventoryException;
import com.lrj.wms.inventory.inventory.ReservationLineInput;
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
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S4-07：同 attempt 严格匹配所有者。换 branch/XID 不能接管，原身份重放不多占。 */
class ReservationOwnerIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:00:00Z");
    private static final String DIGEST = "e".repeat(64);
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
        Configuration config = new Configuration(new Environment("owner", new JdbcTransactionFactory(), source));
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
    void replayKeepsOwnerAndRejectsForeignBranch() {
        StockBucketKey bucket = StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-O",
                MasterdataCodes.NO_LOT, InventoryCodes.QUALITY_GOOD);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        String reservationId;
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.receive("ENT-1", "WH-A", "OP-RCV-O", "DOC", "ACTOR", bucket, Quantity.parse("10", 0));
            reservationId = inventory.reserveTried("ENT-1", "WH-A", "OP-TRY-O", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                    "xid-owner", 21L, ReservationTccAction.ACTION_NAME, 1L, DIGEST,
                    List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-O")));
            String replay = inventory.reserveTried("ENT-1", "WH-A", "OP-TRY-O2", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                    "xid-owner", 21L, ReservationTccAction.ACTION_NAME, 1L, DIGEST,
                    List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-O")));
            assertEquals(reservationId, replay);
            InventoryException digest = assertThrows(InventoryException.class,
                    () -> inventory.reserveTried("ENT-1", "WH-A", "OP-TRY-OD", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                            "xid-owner", 21L, ReservationTccAction.ACTION_NAME, 1L, "f".repeat(64),
                            List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-O"))));
            assertEquals("TCC_CONTEXT_MISMATCH", digest.code());
            InventoryException owner = assertThrows(InventoryException.class,
                    () -> inventory.reserveTried("ENT-1", "WH-A", "OP-TRY-OX", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                            "xid-other", 22L, ReservationTccAction.ACTION_NAME, 1L, DIGEST,
                            List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-O"))));
            assertEquals("TCC_OWNER_CONFLICT", owner.code());
            session.commit();
        }
        assertEquals(ReservationState.TRIED,
                jdbc.queryForObject("SELECT state FROM reservation WHERE id=?", String.class, reservationId));
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-O'",
                BigDecimal.class).compareTo(new BigDecimal("3.000000")));
        try (SqlSession session = sessions.openSession(false)) {
            InventoryApplicationService inventory = new InventoryApplicationService(session, clock);
            inventory.cancelTried("ENT-1", "WH-A", "OP-CXL-O", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                    "xid-owner", 21L, ReservationTccAction.ACTION_NAME);
            InventoryException terminal = assertThrows(InventoryException.class,
                    () -> inventory.reserveTried("ENT-1", "WH-A", "OP-TRY-OT", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                            "xid-owner", 21L, ReservationTccAction.ACTION_NAME, 1L, DIGEST,
                            List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-O"))));
            assertEquals("TCC_BRANCH_TERMINAL", terminal.code());
            InventoryException afterCancel = assertThrows(InventoryException.class,
                    () -> inventory.reserveTried("ENT-1", "WH-A", "OP-TRY-ON", "DOC", "ACTOR", "ALLOC-O", "ATT-O",
                            "xid-new", 23L, ReservationTccAction.ACTION_NAME, 1L, DIGEST,
                            List.of(new ReservationLineInput(bucket, Quantity.parse("3", 0), "OL-O"))));
            assertEquals("TCC_OWNER_CONFLICT", afterCancel.code());
            session.commit();
        }
        assertEquals(0, jdbc.queryForObject("SELECT reserved_qty FROM stock_balance WHERE sku_id='SKU-O'",
                BigDecimal.class).compareTo(new BigDecimal("0.000000")));
        System.out.println("S4_OWNER: same identity replay; foreign branch rejected; cancelled Try not reused");
    }
}
