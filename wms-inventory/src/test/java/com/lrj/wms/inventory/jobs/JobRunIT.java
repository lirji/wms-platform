package com.lrj.wms.inventory.jobs;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** S7-02：run/shard 持久化、活跃唯一、心跳与回收后旧 fence 失败。 */
class JobRunIT {
    private static final Instant NOW = Instant.parse("2026-09-12T05:00:00Z");
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
        Configuration config = new Configuration(new Environment("jobs", new JdbcTransactionFactory(), source));
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
    void planIsIdempotentAndActiveShardIsUnique() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            JobRunService jobs = new JobRunService(session, clock);
            Map<String, Object> first = jobs.plan("ENT-1", "WH-A",
                    "ENT-1/expiryEligibilitySweep/WH-A/W1/v1", WmsJobCatalog.EXPIRY_ELIGIBILITY_SWEEP,
                    "WH-A", "W1", "v1", List.of("WH-A/LOT"));
            Map<String, Object> replay = jobs.plan("ENT-1", "WH-A",
                    "ENT-1/expiryEligibilitySweep/WH-A/W1/v1", WmsJobCatalog.EXPIRY_ELIGIBILITY_SWEEP,
                    "WH-A", "W1", "v1", List.of("WH-A/LOT"));
            assertEquals(first.get("runId"), replay.get("runId"));
            assertEquals(Boolean.TRUE, replay.get("replayed"));
            JobRunException conflict = assertThrows(JobRunException.class, () -> jobs.plan("ENT-1", "WH-A",
                    "ENT-1/expiryEligibilitySweep/WH-A/W2/v1", WmsJobCatalog.EXPIRY_ELIGIBILITY_SWEEP,
                    "WH-A", "W2", "v1", List.of("WH-A/LOT")));
            assertEquals("DUPLICATE_ACTIVE_SHARD", conflict.code());
            session.rollback();
        }
    }

    @Test
    void heartbeatReclaimAndStaleFenceAreEnforced() {
        MutableClock clock = new MutableClock(NOW);
        try (SqlSession session = sessions.openSession(false)) {
            JobRunService jobs = new JobRunService(session, clock);
            jobs.plan("ENT-1", "WH-A", "ENT-1/jobLeaseRecovery/WH-A/W1/v1", WmsJobCatalog.JOB_LEASE_RECOVERY,
                    "WH-A", "W1", "v1", List.of("WH-A/LEASE"));
            Map<String, Object> claimed = jobs.claim("ENT-1", "WH-A", WmsJobCatalog.JOB_LEASE_RECOVERY, "worker-a",
                    Duration.ofSeconds(10));
            assertEquals(Boolean.TRUE, claimed.get("claimed"));
            String shardId = String.valueOf(claimed.get("shardId"));
            long epoch = ((Number) claimed.get("claimEpoch")).longValue();
            String fence = String.valueOf(claimed.get("fenceToken"));
            jobs.heartbeat("ENT-1", "WH-A", shardId, epoch, fence, Duration.ofSeconds(10));
            clock.advance(Duration.ofSeconds(30));
            assertEquals(1, jobs.reclaimExpired("ENT-1", "WH-A"));
            JobRunException stale = assertThrows(JobRunException.class,
                    () -> jobs.complete("ENT-1", "WH-A", shardId, epoch, fence));
            assertEquals("STALE_FENCE", stale.code());
            Map<String, Object> again = jobs.claim("ENT-1", "WH-A", WmsJobCatalog.JOB_LEASE_RECOVERY, "worker-b",
                    Duration.ofSeconds(10));
            assertEquals(Boolean.TRUE, again.get("claimed"));
            assertNotEquals(fence, again.get("fenceToken"));
            assertEquals("SUCCEEDED", jobs.complete("ENT-1", "WH-A", String.valueOf(again.get("shardId")),
                    ((Number) again.get("claimEpoch")).longValue(), String.valueOf(again.get("fenceToken"))).get("state"));
            session.rollback();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
