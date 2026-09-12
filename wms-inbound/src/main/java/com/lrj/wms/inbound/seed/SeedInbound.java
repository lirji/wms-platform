package com.lrj.wms.inbound.seed;

import com.lrj.wms.inbound.receipt.InboundReceiptService;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

/** 隔离测试库入库演示单。复跑按外部单号去重，不写库存库。 */
public final class SeedInbound {
    public static final String ENTERPRISE = "ENT-DEMO";
    public static final String OWNER = "OWNER-SELF";
    public static final String SOURCE = "SEED";

    private SeedInbound() {
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
        System.out.println("seed-inbound ok " + counts);
    }

    public static void requireIsolated(String jdbcUrl) {
        String value = jdbcUrl.toLowerCase(Locale.ROOT);
        if (value.contains("43306") || value.contains("dev-infra") || value.contains("dev_infra")) {
            throw new IllegalArgumentException("拒绝共享dev-infra数据库");
        }
        if (!value.contains("wms_inbound")) {
            throw new IllegalArgumentException("必须显式指向wms_inbound测试库");
        }
    }

    public static Map<String, Integer> seed(DataSource dataSource, Clock clock) {
        return seed(dataSource,clock,new com.lrj.wms.runtime.db.DatabaseTimePolicy("UTC", ""));
    }

    /** 隔离库种子也验证时间来源，避免混入另一时区的演示数据。 */
    public static Map<String, Integer> seed(DataSource dataSource, Clock clock, com.lrj.wms.runtime.db.DatabaseTimePolicy time) {
        var migration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        time.initialize(dataSource,migration::migrate);
        SqlSessionFactory sessions = sessions(dataSource);
        Timestamp now = Timestamp.from(clock.instant());
        try (SqlSession session = sessions.openSession(false)) {
            SeedInboundMapper mapper = session.getMapper(SeedInboundMapper.class);
            insertApproved(mapper, "INB-DEMO-OPEN-A", "WH-A", "DEMO-ASN-OPEN-A", now,
                    List.of(line("INB-DEMO-OPEN-A-L1", "L1", "SKU-STD", "30", "0"),
                            line("INB-DEMO-OPEN-A-L2", "L2", "SKU-LOT", "12", "0")));
            insertApproved(mapper, "INB-DEMO-NEAR-A", "WH-A", "DEMO-ASN-NEAR-A", now,
                    List.of(line("INB-DEMO-NEAR-A-L1", "L1", "SKU-NEAR", "10", "0")));
            insertReceiving(mapper, now);
            insertApproved(mapper, "INB-DEMO-OPEN-B", "WH-B", "DEMO-ASN-OPEN-B", now,
                    List.of(line("INB-DEMO-OPEN-B-L1", "L1", "SKU-STD", "20", "0")));
            session.commit();
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("orders", mapper.countOrders(ENTERPRISE));
            counts.put("lines", mapper.countLines(ENTERPRISE));
            return counts;
        }
    }

    private static void insertApproved(SeedInboundMapper mapper, String orderId, String warehouseId, String externalNo,
            Timestamp now, List<Line> lines) {
        mapper.insertOrderIgnore(orderId, ENTERPRISE, warehouseId, SOURCE, externalNo, OWNER,
                InboundReceiptService.STATUS_APPROVED, now);
        for (Line line : lines) {
            mapper.insertLineIgnore(line.id(), ENTERPRISE, warehouseId, orderId, line.externalLineId(), line.skuId(),
                    line.expected(), line.received(), "EA", now);
        }
    }

    private static void insertReceiving(SeedInboundMapper mapper, Timestamp now) {
        mapper.insertOrderIgnore("INB-DEMO-RCV-A", ENTERPRISE, "WH-A", SOURCE, "DEMO-ASN-RCV-A", OWNER,
                InboundReceiptService.STATUS_RECEIVING, now);
        mapper.insertLineIgnore("INB-DEMO-RCV-A-L1", ENTERPRISE, "WH-A", "INB-DEMO-RCV-A", "L1", "SKU-STD",
                new BigDecimal("20"), new BigDecimal("8"), "EA", now);
    }

    private static Line line(String id, String externalLineId, String skuId, String expected, String received) {
        return new Line(id, externalLineId, skuId, new BigDecimal(expected), new BigDecimal(received));
    }

    private record Line(String id, String externalLineId, String skuId, BigDecimal expected, BigDecimal received) {
    }

    private static SqlSessionFactory sessions(DataSource dataSource) {
        Configuration config = new Configuration(new Environment("seed", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(SeedInboundMapper.class);
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
