package com.lrj.wms.inventory.masterdata;

import com.lrj.wms.inventory.masterdata.infrastructure.MasterdataMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
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
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(jdbc);
        source.setUser(required(flags, "username", "WMS_SEED_DB_USER"));
        source.setPassword(optionalPassword(flags));
        Set<String> warehouses = Set.copyOf(List.of(required(flags, "warehouses", "WMS_SEED_WAREHOUSES").split(",")));
        Map<String, Integer> counts = seed(source, Clock.systemUTC(), warehouses);
        System.out.println("seed-local ok " + counts);
    }

    /** 对单个物理库存库执行迁移并幂等写入指定仓的种子。 */
    public static Map<String, Integer> seed(DataSource dataSource, Clock clock, Set<String> warehouseIds) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
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
            }
            session.commit();
            MasterdataMapper mapper = session.getMapper(MasterdataMapper.class);
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("warehouses", mapper.countWarehouses(SeedCatalog.ENTERPRISE));
            counts.put("skus", mapper.countSkus(SeedCatalog.ENTERPRISE));
            counts.put("lots", mapper.countLots(SeedCatalog.ENTERPRISE));
            counts.put("grants", mapper.countGrants(SeedCatalog.ENTERPRISE));
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
        config.addMapper(MasterdataMapper.class);
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
