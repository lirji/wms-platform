package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.count.CountMapper;
import com.lrj.wms.inventory.count.CountService;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.lrj.wms.inventory.masterdata.infrastructure.SeedStockMapper;
import com.lrj.wms.inventory.query.InventoryProjectionService;
import com.lrj.wms.inventory.query.ProjectionMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

/**
 * 仅写入显式隔离测试库的幂等种子。拒绝共享 dev-infra。
 */
public final class SeedLocal {
    private SeedLocal() {
    }

    /** 命令行入口：标志或环境变量 WMS_SEED_*，避免把口令写进进程参数。 */
    public static void main(String[] args) {
        Map<String, String> flags = flags(args);
        String jdbc = required(flags, "jdbc", "WMS_SEED_JDBC_URL");
        requireIsolated(jdbc);
        var time = new com.lrj.wms.runtime.db.DatabaseTimePolicy(System.getenv().getOrDefault("WMS_RUNTIME_DB_TIME_STORAGE_ZONE", "UTC"),
                System.getenv().getOrDefault("WMS_RUNTIME_DB_TIME_LEGACY_EVIDENCE", ""));
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(jdbc,time.storageZone()));
        source.setUser(required(flags, "username", "WMS_SEED_DB_USER"));
        source.setPassword(optionalPassword(flags));
        Set<String> warehouses = Set.copyOf(List.of(required(flags, "warehouses", "WMS_SEED_WAREHOUSES").split(",")));
        Map<String, Integer> counts = seed(source, Clock.systemUTC(), warehouses, time);
        System.out.println("seed-local ok " + counts);
    }

    /** 对单个物理库存库执行迁移并幂等写入指定仓的种子。 */
    public static Map<String, Integer> seed(DataSource dataSource, Clock clock, Set<String> warehouseIds) {
        return seed(dataSource,clock,warehouseIds,new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", ""));
    }

    /** 隔离库种子也验证时间来源，避免混入另一时区的演示数据。 */
    public static Map<String, Integer> seed(DataSource dataSource, Clock clock, Set<String> warehouseIds, com.lrj.wms.runtime.db.DatabaseTimePolicy time) {
        var migration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        time.initialize(dataSource,migration::migrate);
        SqlSessionFactory sessions = sessions(dataSource);
        try (SqlSession session = sessions.openSession(false)) {
            MasterdataService service = new MasterdataService(session, clock);
            Instant now = clock.instant();
            for (SeedCatalog.SkuSeed sku : SeedCatalog.skus()) {
                service.createSku(sku.policy(), sku.unitRowId());
                if (sku.extraCase()) {
                    service.addSkuUnit(sku.policy(), sku.unitRowId() + "-CS", "CS", SeedCatalog.twelve(), BigDecimal.ONE,
                            BigDecimal.ONE);
                }
            }
            for (SeedCatalog.WarehouseSeed warehouse : SeedCatalog.warehouses()) {
                if (!warehouseIds.contains(warehouse.id())) {
                    continue;
                }
                service.createWarehouse(warehouse.id(), SeedCatalog.ENTERPRISE, warehouse.code(), warehouse.name(),
                        warehouse.timezone());
                seedLocations(service, warehouse.id());
                seedLots(service, warehouse.id(), now);
                seedGrants(session.getMapper(MasterdataMapper.class), warehouse.id(), now);
                seedOpeningStock(session, warehouse.id(), now);
                seedDraftCount(session, clock, warehouse.id());
            }
            session.commit();
            MasterdataMapper mapper = session.getMapper(MasterdataMapper.class);
            SeedStockMapper stock = session.getMapper(SeedStockMapper.class);
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("warehouses", mapper.countWarehouses(SeedCatalog.ENTERPRISE));
            counts.put("skus", mapper.countSkus(SeedCatalog.ENTERPRISE));
            counts.put("skuUnits", mapper.countSkuUnits(SeedCatalog.ENTERPRISE));
            counts.put("lots", mapper.countLots(SeedCatalog.ENTERPRISE));
            counts.put("grants", mapper.countGrants(SeedCatalog.ENTERPRISE));
            counts.put("balances", stock.countBalances(SeedCatalog.ENTERPRISE));
            counts.put("ledgers", stock.countLedgers(SeedCatalog.ENTERPRISE));
            counts.put("views", stock.countViews(SeedCatalog.ENTERPRISE));
            counts.put("countPlans", stock.countPlans(SeedCatalog.ENTERPRISE));
            return counts;
        }
    }

    /** 拒绝共享基础设施连接串。 */
    public static void requireIsolated(String jdbcUrl) {
        String value = jdbcUrl.toLowerCase(Locale.ROOT);
        if (value.contains("43306") || value.contains("dev-infra") || value.contains("dev_infra")) {
            throw new IllegalArgumentException("拒绝共享dev-infra数据库");
        }
        if (!value.contains("wms_inventory")) {
            throw new IllegalArgumentException("必须显式指向wms_inventory测试库");
        }
    }

    private static void seedLocations(MasterdataService service, String warehouseId) {
        service.createLocation(warehouseId + "-RCV", warehouseId + "-RCV-GATE", SeedCatalog.ENTERPRISE, warehouseId,
                "RCV", "IN", "RECEIVING", null, null);
        service.createLocation(warehouseId + "-STG", warehouseId + "-STG-GATE", SeedCatalog.ENTERPRISE, warehouseId,
                "STG", "OUT", "STAGING", null, null);
        service.createLocation(warehouseId + "-STO", warehouseId + "-STO-GATE", SeedCatalog.ENTERPRISE, warehouseId,
                "STO", "A", "STORAGE", new BigDecimal("1000"), "EA");
        service.createLocation(warehouseId + "-SHP", warehouseId + "-SHP-GATE", SeedCatalog.ENTERPRISE, warehouseId,
                "SHP", "OUT", "SHIPPING", null, null);
    }

    private static void seedLots(MasterdataService service, String warehouseId, Instant now) {
        var lotSku = SeedCatalog.skus().get(1).policy();
        service.createLot(lotSku, warehouseId + "-LOT-STD", warehouseId, SeedCatalog.OWNER, "LOT-STD",
                SeedCatalog.ENTERPRISE + "/" + SeedCatalog.OWNER + "/SKU-LOT/LOT-STD", null, null, null, 0);
        var near = SeedCatalog.skus().get(3).policy();
        Instant produced = now.minusSeconds(86400 * 10);
        service.createLot(near, warehouseId + "-LOT-NEAR", warehouseId, SeedCatalog.OWNER, "LOT-NEAR",
                SeedCatalog.ENTERPRISE + "/" + SeedCatalog.OWNER + "/SKU-NEAR/LOT-NEAR", produced,
                SeedCatalog.nearExpiry(now), null, 0);
        var expired = SeedCatalog.skus().get(4).policy();
        service.createLot(expired, warehouseId + "-LOT-EXP", warehouseId, SeedCatalog.OWNER, "LOT-EXP",
                SeedCatalog.ENTERPRISE + "/" + SeedCatalog.OWNER + "/SKU-EXPIRED/LOT-EXP", produced.minusSeconds(86400 * 20),
                SeedCatalog.alreadyExpired(now), null, 0);
    }

    /** 开账余额 + 流水 + 当前世代投影。复跑保持原数量。 */
    private static void seedOpeningStock(SqlSession session, String warehouseId, Instant now) {
        Timestamp ts = Timestamp.from(now);
        SeedStockMapper stock = session.getMapper(SeedStockMapper.class);
        ProjectionMapper views = session.getMapper(ProjectionMapper.class);
        views.insertCheckpointIgnore(warehouseId + "-INV-VIEW", SeedCatalog.ENTERPRISE, warehouseId,
                InventoryProjectionService.NAME, ts);
        Map<String, Object> checkpoint = views.lockCheckpoint(SeedCatalog.ENTERPRISE, warehouseId,
                InventoryProjectionService.NAME);
        long generation = checkpoint == null ? 0L : ((Number) checkpoint.get("live_generation")).longValue();
        for (SeedCatalog.StockSeed seed : SeedCatalog.stocks()) {
            String balanceId = seed.balanceId(warehouseId);
            stock.insertBalanceIgnore(balanceId, SeedCatalog.ENTERPRISE, warehouseId, SeedCatalog.OWNER,
                    seed.locationId(warehouseId), seed.skuId(), seed.lotId(warehouseId), seed.qualityCode(),
                    seed.onHandQty(), ts);
            stock.insertLedgerIgnore("LED-" + balanceId, SeedCatalog.ENTERPRISE, warehouseId, "OP-" + balanceId,
                    balanceId, seed.onHandQty(), InventoryCodes.REASON_RECEIVE, SeedCatalog.OPENING_DOCUMENT,
                    SeedCatalog.ACTOR, ts);
            stock.insertViewIgnore(balanceId, SeedCatalog.ENTERPRISE, warehouseId, generation, SeedCatalog.OWNER,
                    seed.locationId(warehouseId), seed.skuId(), seed.lotId(warehouseId), seed.qualityCode(),
                    seed.onHandQty(), ts);
        }
    }

    /** 草稿盘点，不排空不冻结门禁。 */
    private static void seedDraftCount(SqlSession session, Clock clock, String warehouseId) {
        new CountService(session, clock).create(SeedCatalog.ENTERPRISE, warehouseId,
                SeedCatalog.countPlanId(warehouseId), CountService.REASON_COUNT,
                List.of(SeedCatalog.storageLocation(warehouseId)));
    }

    private static void seedGrants(MasterdataMapper mapper, String warehouseId, Instant now) {
        java.sql.Timestamp ts = java.sql.Timestamp.from(now);
        String operator = warehouseId.equals(SeedCatalog.WAREHOUSE_A) ? "wms-wh-a" : "wms-wh-b";
        mapper.insertGrant(warehouseId + "-" + operator + "-read", SeedCatalog.ENTERPRISE, operator, warehouseId,
                "masterdata.read", ts);
        mapper.insertGrant(warehouseId + "-" + operator + "-write", SeedCatalog.ENTERPRISE, operator, warehouseId,
                "masterdata.write", ts);
        mapper.insertGrant(warehouseId + "-ops-read", SeedCatalog.ENTERPRISE, "wms-ops", warehouseId, "masterdata.read", ts);
        mapper.insertGrant(warehouseId + "-ops-write", SeedCatalog.ENTERPRISE, "wms-ops", warehouseId, "masterdata.write",
                ts);
    }

    private static SqlSessionFactory sessions(DataSource dataSource) {
        Configuration config = new Configuration(new Environment("seed", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(MasterdataMapper.class);
        config.addMapper(InventoryMapper.class);
        config.addMapper(CountMapper.class);
        config.addMapper(ProjectionMapper.class);
        config.addMapper(SeedStockMapper.class);
        return new SqlSessionFactoryBuilder().build(config);
    }

    private static Map<String, String> flags(String[] args) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                flags.put(args[i].substring(2), args[++i]);
            }
        }
        return flags;
    }

    private static String required(Map<String, String> flags, String name, String envName) {
        String value = firstNonBlank(flags.get(name), System.getenv(envName));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 --" + name + " 或 " + envName);
        }
        return value;
    }

    private static String optionalPassword(Map<String, String> flags) {
        if (flags.containsKey("password")) {
            return flags.get("password");
        }
        String env = System.getenv("WMS_SEED_DB_PASSWORD");
        return env == null ? "" : env;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
