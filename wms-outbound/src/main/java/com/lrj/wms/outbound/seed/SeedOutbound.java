package com.lrj.wms.outbound.seed;

import com.lrj.wms.outbound.order.OutboundOrderMapper;
import com.lrj.wms.outbound.order.OutboundOrderService;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
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

/**
 * 隔离测试库出库演示单。仓内 ALLOCATED 只表示单据状态，不是 TCC 整单成功。
 */
public final class SeedOutbound {
    public static final String ENTERPRISE = "ENT-DEMO";
    public static final String OWNER = "OWNER-SELF";

    private SeedOutbound() {
    }

    public static void main(String[] args) {
        Map<String, String> flags = flags(args);
        String jdbc = required(flags, "jdbc", "WMS_SEED_JDBC_URL");
        requireIsolated(jdbc);
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(jdbc);
        source.setUser(required(flags, "username", "WMS_SEED_DB_USER"));
        source.setPassword(optionalPassword(flags));
        Map<String, Integer> counts = seed(source, Clock.systemUTC());
        System.out.println("seed-outbound ok " + counts);
    }

    public static void requireIsolated(String jdbcUrl) {
        String value = jdbcUrl.toLowerCase(Locale.ROOT);
        if (value.contains("43306") || value.contains("dev-infra") || value.contains("dev_infra")) {
            throw new IllegalArgumentException("拒绝共享dev-infra数据库");
        }
        if (!value.contains("wms_outbound")) {
            throw new IllegalArgumentException("必须显式指向wms_outbound测试库");
        }
    }

    public static Map<String, Integer> seed(DataSource dataSource, Clock clock) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        SqlSessionFactory sessions = sessions(dataSource);
        Timestamp now = Timestamp.from(clock.instant());
        try (SqlSession session = sessions.openSession(false)) {
            OutboundOrderMapper mapper = session.getMapper(OutboundOrderMapper.class);
            SeedOutboundMapper countsMapper = session.getMapper(SeedOutboundMapper.class);
            mapper.insertOrderIgnore("OB-DEMO-ALLOC-A", ENTERPRISE, "WH-A", "FF-DEMO-PLANNED", "ATT-DEMO-PLANNED",
                    OWNER, "AUTH-DEMO-A", OutboundOrderService.STATUS_ALLOCATED, now);
            mapper.insertLineIgnore("OB-DEMO-ALLOC-A-L1", ENTERPRISE, "WH-A", "OB-DEMO-ALLOC-A", "FF-DEMO-PLANNED-L1",
                    "SKU-STD", new BigDecimal("6"), "EA", now);
            mapper.insertOrderIgnore("OB-DEMO-ALLOC-B", ENTERPRISE, "WH-B", "FF-DEMO-WHB", "ATT-DEMO-WHB", OWNER,
                    "AUTH-DEMO-B", OutboundOrderService.STATUS_ALLOCATED, now);
            mapper.insertLineIgnore("OB-DEMO-ALLOC-B-L1", ENTERPRISE, "WH-B", "OB-DEMO-ALLOC-B", "FF-DEMO-WHB-L1",
                    "SKU-STD", new BigDecimal("10"), "EA", now);
            session.commit();
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("orders", countsMapper.countOrders(ENTERPRISE));
            counts.put("lines", countsMapper.countLines(ENTERPRISE));
            return counts;
        }
    }

    private static SqlSessionFactory sessions(DataSource dataSource) {
        Configuration config = new Configuration(new Environment("seed", new JdbcTransactionFactory(), dataSource));
        config.addMapper(OutboundOrderMapper.class);
        config.addMapper(SeedOutboundMapper.class);
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
