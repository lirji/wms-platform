package com.lrj.wms.fulfillment.seed;

import com.lrj.wms.fulfillment.AllocationPlan;
import com.lrj.wms.fulfillment.FulfillmentService;
import com.lrj.wms.fulfillment.TransferService;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
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

/** 隔离测试库履约/调拨演示单。attempt 只写 PLANNED，不发明 TCC ALLOCATED。 */
public final class SeedFulfillment {
    public static final String ENTERPRISE = "ENT-DEMO";

    private SeedFulfillment() {
    }

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
        Map<String, Integer> counts = seed(source, Clock.systemUTC(), time);
        System.out.println("seed-fulfillment ok " + counts);
    }

    public static void requireIsolated(String jdbcUrl) {
        String value = jdbcUrl.toLowerCase(Locale.ROOT);
        if (value.contains("43306") || value.contains("dev-infra") || value.contains("dev_infra")) {
            throw new IllegalArgumentException("拒绝共享dev-infra数据库");
        }
        if (!value.contains("wms_fulfillment")) {
            throw new IllegalArgumentException("必须显式指向wms_fulfillment测试库");
        }
    }

    public static Map<String, Integer> seed(DataSource dataSource, Clock clock) {
        return seed(dataSource,clock,new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", ""));
    }

    /** 隔离库种子也验证时间来源，避免混入另一时区的演示数据。 */
    public static Map<String, Integer> seed(DataSource dataSource, Clock clock, com.lrj.wms.runtime.db.DatabaseTimePolicy time) {
        var migration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/fulfillment").load();
        time.initialize(dataSource,migration::migrate);
        SqlSessionFactory sessions = sessions(dataSource);
        Timestamp now = Timestamp.from(clock.instant());
        try (SqlSession session = sessions.openSession(false)) {
            SeedFulfillmentMapper mapper = session.getMapper(SeedFulfillmentMapper.class);
            seedOpenOrder(mapper, now);
            seedPlannedAttempt(mapper, clock, now);
            seedTransfer(mapper, now);
            session.commit();
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("orders", mapper.countOrders(ENTERPRISE));
            counts.put("attempts", mapper.countAttempts(ENTERPRISE));
            counts.put("transfers", mapper.countTransfers(ENTERPRISE));
            return counts;
        }
    }

    private static void seedOpenOrder(SeedFulfillmentMapper mapper, Timestamp now) {
        String digest = sha256("ENT-DEMO|OMS|SO-DEMO-OPEN|SKU-STD|8|EA");
        mapper.insertOrderIgnore("FF-DEMO-OPEN", ENTERPRISE, "OMS", "SO-DEMO-OPEN", digest, FulfillmentService.ORDER_OPEN,
                0, now);
        mapper.insertLineIgnore("FF-DEMO-OPEN-L1", ENTERPRISE, "FF-DEMO-OPEN", "L1", "SKU-STD", new BigDecimal("8"),
                "EA", now);
    }

    private static void seedPlannedAttempt(SeedFulfillmentMapper mapper, Clock clock, Timestamp now) {
        String digest = sha256("ENT-DEMO|OMS|SO-DEMO-PLANNED|SKU-STD|6|EA");
        mapper.insertOrderIgnore("FF-DEMO-PLANNED", ENTERPRISE, "OMS", "SO-DEMO-PLANNED", digest,
                FulfillmentService.ORDER_OPEN, 0, now);
        mapper.insertLineIgnore("FF-DEMO-PLANNED-L1", ENTERPRISE, "FF-DEMO-PLANNED", "L1", "SKU-STD",
                new BigDecimal("6"), "EA", now);
        List<Map<String, Object>> lines = List.of(Map.of("warehouseId", "WH-A", "orderLineId", "L1", "skuId", "SKU-STD",
                "qty", new BigDecimal("6"), "baseUnit", "EA"));
        Timestamp deadline = Timestamp.from(clock.instant().plus(Duration.ofDays(30)));
        mapper.insertAttemptIgnore("ATT-DEMO-PLANNED", ENTERPRISE, "FF-DEMO-PLANNED", FulfillmentService.ATTEMPT_PLANNED,
                deadline, FulfillmentService.participantHash(Set.of("WH-A")), AllocationPlan.digest(lines), now);
        mapper.insertParticipantIgnore("PAR-DEMO-PLANNED-A", ENTERPRISE, "ATT-DEMO-PLANNED", "WH-A",
                FulfillmentService.PARTICIPANT_PLANNED, now);
        mapper.insertParticipantLineIgnore("PARL-DEMO-PLANNED-A-L1", ENTERPRISE, "PAR-DEMO-PLANNED-A", "L1", "SKU-STD",
                new BigDecimal("6"), "EA", now);
        Map<String, Object> order = mapper.lockOrder(ENTERPRISE, "FF-DEMO-PLANNED");
        if (order != null && (order.get("active_attempt_id") == null || String.valueOf(order.get("active_attempt_id")).isBlank())) {
            mapper.casActiveAttempt(ENTERPRISE, "FF-DEMO-PLANNED", "ATT-DEMO-PLANNED", null,
                    ((Number) order.get("version")).longValue(), now);
        }
    }

    private static void seedTransfer(SeedFulfillmentMapper mapper, Timestamp now) {
        mapper.insertTransferIgnore("TR-DEMO-AB", ENTERPRISE, "WH-A", "WH-B", TransferService.STATUS_OPEN, now);
        mapper.insertTransferLegIgnore("TR-DEMO-AB-SRC", ENTERPRISE, "TR-DEMO-AB", "WH-A", "SOURCE",
                TransferService.STATUS_OPEN, now);
        mapper.insertTransferLegIgnore("TR-DEMO-AB-TGT", ENTERPRISE, "TR-DEMO-AB", "WH-B", "TARGET",
                TransferService.STATUS_OPEN, now);
        mapper.insertTransferLineIgnore("TR-DEMO-AB-L1", ENTERPRISE, "TR-DEMO-AB", "SKU-STD", "NO_LOT", "NO_LOT",
                new BigDecimal("6"), now);
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static SqlSessionFactory sessions(DataSource dataSource) {
        Configuration config = new Configuration(new Environment("seed", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(SeedFulfillmentMapper.class);
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
        String value = flags.get(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(envName);
        }
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
}
