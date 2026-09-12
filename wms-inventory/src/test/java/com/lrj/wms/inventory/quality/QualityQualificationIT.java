package com.lrj.wms.inventory.quality;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** S3-01：质量资格按 inspection 版本防乱序。 */
class QualityQualificationIT {
    private static final Instant NOW = Instant.parse("2026-09-11T07:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        DataSource dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        Configuration config = new Configuration(new Environment("inventory", new JdbcTransactionFactory(), dataSource));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(QualityQualificationMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void newerVersionWinsAndStaleEventKeepsCurrent() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            QualityQualificationService service = new QualityQualificationService(session, clock);
            Map<String, Object> first = service.apply("ENT-1", "WH-A", "INSP-1", 2L, "SKU-1", "NO_LOT", "ACCEPTED",
                    "CMD-Q2");
            Map<String, Object> stale = service.apply("ENT-1", "WH-A", "INSP-1", 1L, "SKU-1", "NO_LOT", "REJECTED",
                    "CMD-Q1");
            Map<String, Object> newer = service.apply("ENT-1", "WH-A", "INSP-1", 3L, "SKU-1", "NO_LOT", "REJECTED",
                    "CMD-Q3");
            assertEquals(2L, ((Number) first.get("sourceVersion")).longValue());
            assertEquals("ACCEPTED", stale.get("resultCode"));
            assertEquals(2L, ((Number) stale.get("sourceVersion")).longValue());
            assertEquals("REJECTED", newer.get("resultCode"));
            assertEquals(3L, ((Number) newer.get("sourceVersion")).longValue());
            session.commit();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_qualification WHERE inspection_id='INSP-1'",
                Integer.class));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT result_code FROM quality_qualification WHERE inspection_id='INSP-1'", String.class));
    }
}
