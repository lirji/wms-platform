package com.lrj.wms.runtime;

import com.lrj.wms.runtime.db.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 实际MySQL、上海/美西独立JVM、新库UTC/旧库+08、时间游标及保留DATE语义。 */
class TimeSemanticsIT {
    private static final DatabaseBudget BUDGET=new DatabaseBudget(2,0,1000,500,5,1000,10000);
    private static final String SAMPLE="CREATE TABLE time_sample (id VARCHAR(64) NOT NULL COMMENT '测试标识',created_at DATETIME(6) NOT NULL COMMENT '瞬时字段',business_date DATE NOT NULL COMMENT '业务日期',PRIMARY KEY(id)) COMMENT='隔离时间验证'";
    @Test void preservesInstantsAcrossJvmZonesAndRequiresLegacyEvidence() throws Exception {
        try(var mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("time_utc").withUsername("root").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            Path cursor=Files.createTempFile("wms-time-cursor-",".txt");
            String ddl=policyDdl();
            var utc=new DatabaseTimePolicy("UTC","");
            try(var pool=RuntimeDataSources.create("time-test",mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword(),BUDGET,utc)) {
                var jdbc=new JdbcTemplate(pool);
                utc.initialize(pool,()->{ jdbc.execute(ddl);jdbc.execute(SAMPLE); });
                assertEquals("fresh-schema",jdbc.queryForObject("SELECT evidence_ref FROM database_time_policy",String.class));
                jdbc.execute("CREATE DATABASE time_legacy");
            }
            probe(mysql.getJdbcUrl(),mysql,"UTC","Asia/Shanghai","write",cursor);
            probe(mysql.getJdbcUrl(),mysql,"UTC","America/Los_Angeles","read",cursor);
            String legacyUrl=mysql.getJdbcUrl().replace("/time_utc","/time_legacy");
            try(var pool=RuntimeDataSources.create("legacy-unknown",legacyUrl,mysql.getUsername(),mysql.getPassword(),BUDGET,utc)) {
                var jdbc=new JdbcTemplate(pool);jdbc.execute(SAMPLE);
                jdbc.execute("INSERT INTO time_sample VALUES ('LEGACY','2026-11-01 08:00:00','2026-11-01')");
                assertThrows(IllegalStateException.class,()->utc.initialize(pool,()->fail("必须在迁移前拒绝未知历史时区")));
            }
            var legacy=new DatabaseTimePolicy("+08:00","isolated-history-verified-08");
            try(var pool=RuntimeDataSources.create("legacy-confirmed",legacyUrl,mysql.getUsername(),mysql.getPassword(),BUDGET,legacy)) {
                var jdbc=new JdbcTemplate(pool);legacy.initialize(pool,()->jdbc.execute(ddl));
                assertEquals("+08:00",jdbc.queryForObject("SELECT storage_offset FROM database_time_policy",String.class));
                assertEquals("2026-11-01 08:00:00.000000",jdbc.queryForObject("SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f') FROM time_sample",String.class));
                assertThrows(IllegalStateException.class,()->new DatabaseTimePolicy("UTC","cannot-override").initialize(pool,()->fail("不能覆盖时区")));
            }
            probe(legacyUrl,mysql,"+08:00","America/Los_Angeles","legacy",cursor);
            probe(legacyUrl,mysql,"+08:00","Asia/Shanghai","legacy",cursor);
            assertThrows(IllegalArgumentException.class,()->DatabaseInstants.require(LocalDateTime.now()));
            assertThrows(IllegalArgumentException.class,()->new DatabaseTimePolicy("America/New_York",""));
            assertThrows(IllegalArgumentException.class,()->RuntimeDataSources.withTimeZone("jdbc:mysql://localhost/test?preserveInstants=false","UTC"));
        }
    }
    private static void probe(String url,MySQLContainer mysql,String offset,String zone,String mode,Path cursor) throws Exception {
        Path log=Files.createTempFile("wms-time-probe-",".log");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-Duser.timezone="+zone,
                "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),TimeZoneProbe.class.getName(),mode);
        var env=builder.environment();env.put("PROBE_URL",url);env.put("PROBE_USER",mysql.getUsername());env.put("PROBE_PASSWORD",mysql.getPassword());
        env.put("PROBE_OFFSET",offset);env.put("PROBE_CURSOR",cursor.toString());
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue(process.waitFor(45,TimeUnit.SECONDS),"time probe timeout"); assertEquals(0,process.exitValue(),()->"time probe failed: "+log); }
        finally { if(process.isAlive())process.destroyForcibly(); }
        assertTrue(Files.readString(log).contains("TIME_PROBE_PASS"));
    }
    private static String policyDdl() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.isDirectory(root.resolve("wms-inventory")))root=root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve("wms-inventory/src/main/resources/db/migration/V032__database_time_policy.sql"));
    }
}
