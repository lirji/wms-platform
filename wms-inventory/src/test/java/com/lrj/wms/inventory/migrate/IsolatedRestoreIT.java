package com.lrj.wms.inventory.migrate;

import com.lrj.wms.inventory.inventory.InventoryApplicationService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S9-04：隔离第二库恢复备份并对账。测得本地 RTO/RPO，不是签署的生产目标。
 */
class IsolatedRestoreIT {
    @Test
    void restoreSecondDatabaseAndMeasureLocalRtoRpo() {
        Clock clock = Clock.systemUTC();
        try (var live = mysql("wms_live"); var backup = mysql("wms_restore")) {
            live.start();
            backup.start();
            MysqlDataSource liveSource = datasource(live);
            MysqlDataSource backupSource = datasource(backup);
            Flyway.configure().dataSource(liveSource).locations("classpath:db/migration").load().migrate();
            Flyway.configure().dataSource(backupSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate liveJdbc = new JdbcTemplate(liveSource);
            JdbcTemplate backupJdbc = new JdbcTemplate(backupSource);
            var sessions = new SqlSessionFactoryBuilder().build(config("live", liveSource));
            try (SqlSession session = sessions.openSession(false)) {
                MasterdataService masterdata = new MasterdataService(session, clock);
                masterdata.createWarehouse("WH-A", "ENT-1", "SHA", "上海仓", "Asia/Shanghai");
                masterdata.createLocation("LOC-1", "GATE-1", "ENT-1", "WH-A", "A-01", "A", "STORAGE",
                        new BigDecimal("100"), "EA");
                new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-BAK", "DOC", "ACTOR",
                        bucket(), Quantity.parse("8", 0));
                session.commit();
            }
            Instant backupAt = clock.instant();
            Instant restoreStart = clock.instant();
            try (SqlSession session = sessions.openSession(false)) {
                WarehouseMigrationService copy = new WarehouseMigrationService(session, liveJdbc, backupJdbc, clock);
                copy.prepare("ENT-1", "WH-A", "CELL-LIVE", "CELL-RESTORE");
                copy.copyFull("ENT-1", "WH-A");
                session.commit();
            }
            long rtoMs = Duration.between(restoreStart, clock.instant()).toMillis();
            try (SqlSession session = sessions.openSession(false)) {
                new InventoryApplicationService(session, clock).receive("ENT-1", "WH-A", "OP-AFTER", "DOC", "ACTOR",
                        bucket(), Quantity.parse("3", 0));
                session.commit();
            }
            Instant crashAt = clock.instant();
            assertEquals(0, new BigDecimal("8").compareTo(backupJdbc.queryForObject(
                    "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A'", BigDecimal.class)));
            assertEquals(0, new BigDecimal("11").compareTo(liveJdbc.queryForObject(
                    "SELECT on_hand_qty FROM stock_balance WHERE warehouse_id='WH-A'", BigDecimal.class)));
            assertEquals(0, liveJdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-AFTER'", Integer.class)
                    .compareTo(1));
            assertEquals(0, backupJdbc.queryForObject(
                    "SELECT COUNT(*) FROM stock_ledger WHERE operation_id='OP-AFTER'", Integer.class)
                    .compareTo(0));
            long rpoMs = Duration.between(backupAt, crashAt).toMillis();
            assertTrue(rtoMs >= 0);
            assertTrue(rpoMs >= 0);
            System.out.println("S9_RESTORE: isolated second MySQL; localRtoMs=" + rtoMs + "; localRpoMs=" + rpoMs
                    + "; missingOps=OP-AFTER; not production SLO");
        }
    }

    private static StockBucketKey bucket() {
        return StockBucketKey.of("ENT-1", "WH-A", "OWNER-1", "LOC-1", "SKU-R", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
    }

    private static MySQLContainer mysql(String database) {
        return new MySQLContainer("mysql:8.4.11").withDatabaseName(database)
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
    }

    private static MysqlDataSource datasource(MySQLContainer mysql) {
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }

    private static Configuration config(String name, MysqlDataSource source) {
        Configuration configuration = new Configuration(new Environment(name, new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(configuration);
        configuration.addMapper(MasterdataMapper.class);
        configuration.addMapper(InventoryMapper.class);
        configuration.addMapper(OutboxMapper.class);
        configuration.addMapper(CommandDedupMapper.class);
        configuration.addMapper(WarehouseRouteMapper.class);
        return configuration;
    }
}
