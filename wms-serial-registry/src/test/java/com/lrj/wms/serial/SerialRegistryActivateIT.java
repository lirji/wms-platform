package com.lrj.wms.serial;

import com.mysql.cj.jdbc.MysqlDataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S3-03：CLAIMED 激活为 ACTIVE，他仓与恢复查询。 */
class SerialRegistryActivateIT {
    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/registry").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("registry", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(SerialRegistryMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void claimActivateReplayAndRejectOtherWarehouse() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SerialRegistryService service = new SerialRegistryService(session, clock);
            assertEquals(SerialRegistryService.STATE_CLAIMED,
                    service.claim("ENT-1", "SKU-S", " sn-act ", "WH-A", "OP-ACT").get("state"));
            Map<String, Object> active = service.activate("ENT-1", "SKU-S", "sn-act", "WH-A", "OP-ACT");
            assertEquals(SerialRegistryService.STATE_ACTIVE, active.get("state"));
            assertEquals("WH-A", active.get("ownerWarehouseId"));
            assertEquals(SerialRegistryService.STATE_ACTIVE,
                    service.activate("ENT-1", "SKU-S", "SN-ACT", "WH-A", "OP-ACT").get("state"));
            assertEquals(SerialRegistryService.STATE_ACTIVE, service.get("ENT-1", "SKU-S", "sn-act").get("state"));
            SerialRegistryException other = assertThrows(SerialRegistryException.class,
                    () -> service.activate("ENT-1", "SKU-S", "sn-act", "WH-B", "OP-ACT"));
            assertEquals("SERIAL_OWNER_MISMATCH", other.code());
            assertEquals("SERIAL_OWNER_MISMATCH", assertThrows(SerialRegistryException.class,
                    () -> service.claim("ENT-1", "SKU-S", "SN-ACT", "WH-B", "OP-ACT")).code());
            assertEquals("SERIAL_OPERATION_MISMATCH", assertThrows(SerialRegistryException.class,
                    () -> service.activate("ENT-1", "SKU-S", "SN-ACT", "WH-A", "OTHER-OP")).code());
            session.commit();
        }
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM serial_registry WHERE enterprise_id='ENT-1' AND sku_id='SKU-S' "
                        + "AND normalized_serial='SN-ACT'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM serial_registry WHERE normalized_serial='SN-ACT'",
                Integer.class));
    }
}
