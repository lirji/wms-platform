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

/** S6-03a：登记 MISSING / FOUND_CLAIMED。不是 AC-18/19 生产。 */
class SerialMissingIT {
    private static final Instant NOW = Instant.parse("2026-09-12T04:00:00Z");
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
        config.addMapper(SerialTransferMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void markMissingBlocksClaimAndFoundReactivates() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            SerialRegistryService service = new SerialRegistryService(session, clock);
            service.claim("ENT-1", "SKU-M", " sn-miss ", "WH-A", "OP-M");
            assertEquals(SerialRegistryService.STATE_ACTIVE,
                    service.activate("ENT-1", "SKU-M", "sn-miss", "WH-A", "OP-M").get("state"));
            Map<String, Object> missing = service.markMissing("ENT-1", "SKU-M", "sn-miss", "WH-A", "FACT-1", 1);
            assertEquals(SerialRegistryService.STATE_MISSING, missing.get("state"));
            assertEquals(SerialRegistryService.STATE_MISSING,
                    service.markMissing("ENT-1", "SKU-M", "SN-MISS", "WH-A", "FACT-1", 1).get("state"));
            SerialRegistryException stale = assertThrows(SerialRegistryException.class,
                    () -> service.markMissing("ENT-1", "SKU-M", "sn-miss", "WH-A", "FACT-2", 0));
            assertEquals("STALE_EPOCH", stale.code());
            SerialRegistryException claimBlocked = assertThrows(SerialRegistryException.class,
                    () -> service.claim("ENT-1", "SKU-M", "sn-miss", "WH-A", "OP-M"));
            assertEquals("SERIAL_MISSING", claimBlocked.code());
            SerialRegistryException activateBlocked = assertThrows(SerialRegistryException.class,
                    () -> service.activate("ENT-1", "SKU-M", "sn-miss", "WH-A", "OP-M"));
            assertEquals("SERIAL_MISSING", activateBlocked.code());
            service.claim("ENT-1", "SKU-A", "sn-live", "WH-A", "OP-LIVE");
            service.activate("ENT-1", "SKU-A", "sn-live", "WH-A", "OP-LIVE");
            SerialRegistryException live = assertThrows(SerialRegistryException.class,
                    () -> service.claimFound("ENT-1", "SKU-A", "sn-live", "WH-A", "OP-FOUND"));
            assertEquals("SERIAL_ALREADY_CLAIMED", live.code());
            Map<String, Object> claimed = service.claimFound("ENT-1", "SKU-M", "sn-miss", "WH-A", "OP-FOUND");
            assertEquals(SerialRegistryService.STATE_FOUND_CLAIMED, claimed.get("state"));
            assertEquals(SerialRegistryService.STATE_FOUND_CLAIMED,
                    service.claimFound("ENT-1", "SKU-M", "SN-MISS", "WH-A", "OP-FOUND").get("state"));
            assertEquals(SerialRegistryService.STATE_ACTIVE,
                    service.activateFound("ENT-1", "SKU-M", "sn-miss", "WH-A", "OP-FOUND").get("state"));
            assertEquals(SerialRegistryService.STATE_CLAIMED,
                    service.claimFound("ENT-1", "SKU-M", "sn-new", "WH-A", "OP-NEW").get("state"));
            session.commit();
        }
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM serial_registry WHERE normalized_serial='SN-LIVE'", String.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM serial_registry WHERE normalized_serial='SN-MISS'", String.class));
        assertEquals("CLAIMED", jdbc.queryForObject(
                "SELECT state FROM serial_registry WHERE normalized_serial='SN-NEW'", String.class));
    }
}
