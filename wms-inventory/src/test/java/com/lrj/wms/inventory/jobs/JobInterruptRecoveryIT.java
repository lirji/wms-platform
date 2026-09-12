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

/** S7-05：中断后从检查点续跑；旧 worker 回写被 fence 拒绝。 */
class JobInterruptRecoveryIT {
    private static final Instant START = Instant.parse("2026-09-12T09:00:00Z");
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
        Configuration config = new Configuration(new Environment("interrupt", new JdbcTransactionFactory(), source));
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
    void interruptedWorkerResumesFromCheckpointAndStaleWriteLoses() {
        Clock start = Clock.fixed(START, ZoneOffset.UTC);
        Map<String, Object> first;
        try (SqlSession session = sessions.openSession(false)) {
            JobRunService jobs = new JobRunService(session, start);
            jobs.plan("ENT-1", "WH-A", "ENT-1/jobLeaseRecovery/WH-A/R1/v1", WmsJobCatalog.JOB_LEASE_RECOVERY, "WH-A",
                    "R1", "v1", List.of("WH-A/RESUME"));
            first = jobs.claim("ENT-1", "WH-A", WmsJobCatalog.JOB_LEASE_RECOVERY, "worker-a", Duration.ofSeconds(5));
            assertEquals(Boolean.TRUE, first.get("claimed"));
            jobs.checkpoint("ENT-1", "WH-A", String.valueOf(first.get("shardId")), asLong(first.get("claimEpoch")),
                    String.valueOf(first.get("fenceToken")), "SKU-100");
            session.commit();
        }
        Clock later = Clock.fixed(START.plusSeconds(10), ZoneOffset.UTC);
        try (SqlSession session = sessions.openSession(false)) {
            JobRunService jobs = new JobRunService(session, later);
            assertEquals(1, jobs.reclaimExpired("ENT-1", "WH-A"));
            Map<String, Object> resumed = jobs.claim("ENT-1", "WH-A", WmsJobCatalog.JOB_LEASE_RECOVERY, "worker-b",
                    Duration.ofSeconds(15));
            assertEquals(Boolean.TRUE, resumed.get("claimed"));
            assertEquals("SKU-100", resumed.get("cursorKey"));
            session.commit();
            try (SqlSession stale = sessions.openSession(false)) {
                JobRunService old = new JobRunService(stale, later);
                JobRunException lost = assertThrows(JobRunException.class,
                        () -> old.complete("ENT-1", "WH-A", String.valueOf(first.get("shardId")),
                                asLong(first.get("claimEpoch")), String.valueOf(first.get("fenceToken"))));
                assertEquals("STALE_FENCE", lost.code());
                stale.rollback();
            }
            jobs.complete("ENT-1", "WH-A", String.valueOf(resumed.get("shardId")), asLong(resumed.get("claimEpoch")),
                    String.valueOf(resumed.get("fenceToken")));
            session.commit();
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
