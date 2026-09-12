package com.lrj.wms.serial;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
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

/** S3-02：两仓并发认领同一序列号只有一个有效身份。 */
class SerialRegistryIT {
    private static final Instant NOW = Instant.parse("2026-09-11T08:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_registry")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(mysql.getJdbcUrl());
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration/registry").load().migrate();
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("registry", new JdbcTransactionFactory(), source));
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
    void twoWarehousesConcurrentClaimOnlyOneWins() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        Thread a = new Thread(() -> claim("WH-A", "OP-A", clock, start, done, won, lost));
        Thread b = new Thread(() -> claim("WH-B", "OP-B", clock, start, done, won, lost));
        a.start();
        b.start();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "并发认领应在预算内完成");
        assertEquals(1, won.get());
        assertEquals(1, lost.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_registry WHERE enterprise_id='ENT-1' AND sku_id='SKU-S' "
                        + "AND normalized_serial='SN-1'", Integer.class));
        // 获胜仓取决于真实并发调度；重放必须使用同一仓与原操作，不能假定A仓获胜。
        Map<String, Object> winner = jdbc.queryForMap(
                "SELECT owner_warehouse_id, claim_operation_id FROM serial_registry WHERE normalized_serial='SN-1'");
        String winningWarehouse = String.valueOf(winner.get("owner_warehouse_id"));
        String winningOperation = String.valueOf(winner.get("claim_operation_id"));
        try (SqlSession session = sessions.openSession(false)) {
            Map<String, Object> replay = new SerialRegistryService(session, clock).claim("ENT-1", "SKU-S", " sn-1 ",
                    winningWarehouse, winningOperation);
            assertEquals("CLAIMED", replay.get("state"));
            assertEquals(winningWarehouse, replay.get("ownerWarehouseId"));
            session.commit();
        }
        // 另一仓即使知道获胜操作号，也不能借幂等重放绕过登记归属校验。
        String losingWarehouse = "WH-A".equals(winningWarehouse) ? "WH-B" : "WH-A";
        try (SqlSession session = sessions.openSession(false)) {
            var denied = assertThrows(SerialRegistryException.class,
                    () -> new SerialRegistryService(session, clock).claim("ENT-1", "SKU-S", "SN-1",
                            losingWarehouse, winningOperation));
            assertEquals("SERIAL_OWNER_MISMATCH", denied.code());
        }
        assertEquals(SerialRegistryService.routeBucket("ENT-1", "SKU-S", "SN-1"),
                jdbc.queryForObject("SELECT route_bucket FROM serial_registry WHERE normalized_serial='SN-1'", Integer.class));
    }

    private void claim(String warehouse, String operation, Clock clock, CyclicBarrier start, CountDownLatch done,
            AtomicInteger won, AtomicInteger lost) {
        try {
            start.await();
            try (SqlSession session = sessions.openSession(false)) {
                new SerialRegistryService(session, clock).claim("ENT-1", "SKU-S", "sn-1", warehouse, operation);
                session.commit();
                won.incrementAndGet();
            }
        } catch (SerialRegistryException error) {
            assertEquals("SERIAL_ALREADY_CLAIMED", error.code());
            lost.incrementAndGet();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        } finally {
            done.countDown();
        }
    }
}
