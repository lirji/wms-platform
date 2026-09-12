package com.lrj.wms.inventory.jobs;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S7-03：并发领取只一人成功；被抢占的旧 epoch 提交必须失败。 */
class JobLeasePreemptIT {
    private static final Instant NOW = Instant.parse("2026-09-12T06:00:00Z");
    private static MySQLContainer mysql;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void prepare() {
        mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("wms_inventory")
                .withUsername("wms").withPassword(UUID.randomUUID().toString());
        mysql.start();
        MysqlDataSource source = new MysqlDataSource();
        source.setUrl(com.lrj.wms.runtime.db.RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(), "UTC"));
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        Configuration config = new Configuration(new Environment("lease", new JdbcTransactionFactory(), source));
        com.lrj.wms.runtime.db.DatabaseInstants.configure(config);
        config.addMapper(JobRunMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void cleanup() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    void concurrentClaimAndStaleEpochLose() throws Exception {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession setup = sessions.openSession(false)) {
            new JobRunService(setup, clock).plan("ENT-1", "WH-A", "ENT-1/jobLeaseRecovery/WH-A/P1/v1",
                    WmsJobCatalog.JOB_LEASE_RECOVERY, "WH-A", "P1", "v1", List.of("WH-A/PREEMPT"));
            setup.commit();
        }
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger claimed = new AtomicInteger();
        AtomicReference<Map<String, Object>> winner = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        Runnable racer = () -> {
            try (SqlSession session = sessions.openSession(false)) {
                start.await();
                Map<String, Object> result = new JobRunService(session, clock).claim("ENT-1", "WH-A",
                        WmsJobCatalog.JOB_LEASE_RECOVERY, Thread.currentThread().getName(), Duration.ofSeconds(15));
                session.commit();
                if (Boolean.TRUE.equals(result.get("claimed"))) {
                    claimed.incrementAndGet();
                    winner.set(result);
                }
            } catch (Exception failed) {
                error.compareAndSet(null, failed);
            }
        };
        Thread a = new Thread(racer, "worker-a");
        Thread b = new Thread(racer, "worker-b");
        a.start();
        b.start();
        a.join();
        b.join();
        assertNull(error.get(), () -> String.valueOf(error.get()));
        assertEquals(1, claimed.get());
        Map<String, Object> lease = winner.get();
        long epoch = ((Number) lease.get("claimEpoch")).longValue();
        String fence = String.valueOf(lease.get("fenceToken"));
        String shardId = String.valueOf(lease.get("shardId"));
        try (SqlSession session = sessions.openSession(false)) {
            JobRunService jobs = new JobRunService(session, clock);
            JobRunException stale = assertThrows(JobRunException.class,
                    () -> jobs.complete("ENT-1", "WH-A", shardId, epoch - 1, fence));
            assertEquals("STALE_FENCE", stale.code());
            assertEquals("SUCCEEDED", jobs.complete("ENT-1", "WH-A", shardId, epoch, fence).get("state"));
            session.commit();
        }
    }
}
