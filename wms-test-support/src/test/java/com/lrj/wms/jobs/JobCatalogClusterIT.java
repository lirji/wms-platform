package com.lrj.wms.jobs;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.impl.XxlJobSimpleExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * S7-01：官方 admin 3.4.2 对目录 10 个 BEAN 做双执行器注册与重复触发。
 * 同参数第二次触发不增加业务效果。不是生产集群/分片 SLA，不是 AC-20。
 */
class JobCatalogClusterIT {
    private static final String TOKEN = "s7-xxl-catalog-token";
    private static final String APP = "wms-s7-catalog";
    private static final List<String> HANDLERS = List.of(
            "tccReservationWatch",
            "allocationRecoverySweep",
            "expiryEligibilitySweep",
            "serialTransferRecovery",
            "deviceUnknownResultSweep",
            "stockInternalReconcile",
            "externalReconcileExport",
            "countApplyRecovery",
            "archivePlanner",
            "jobLeaseRecovery");

    static final AtomicInteger runs = new AtomicInteger();
    static final ConcurrentHashMap<String, Integer> effects = new ConcurrentHashMap<>();

    @Test
    void twoExecutorsRegisterAndDuplicateTriggerDoesNotDoubleEffect() throws Exception {
        int portA = freePort();
        int portB = freePort();
        Testcontainers.exposeHostPorts(portA, portB);
        Path schema = schemaFile();
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("xxl_job")
                .withUsername("wms_xxl").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            Testcontainers.exposeHostPorts(mysql.getMappedPort(3306), portA, portB);
            var jdbc = new JdbcTemplate(source(mysql));
            jdbc.execute(Files.readString(schema));
            jdbc.update("INSERT INTO xxl_job_group(app_name,title,address_type,address_list,update_time) VALUES (?,?,0,'',NOW())",
                    APP, "s7-catalog");
            Integer groupId = jdbc.queryForObject("SELECT id FROM xxl_job_group WHERE app_name=?", Integer.class, APP);
            for (String handler : HANDLERS) {
                jdbc.update("INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,schedule_type,"
                                + "misfire_strategy,executor_route_strategy,executor_handler,executor_block_strategy,"
                                + "executor_timeout,executor_fail_retry_count,glue_type,glue_remark,glue_updatetime,"
                                + "trigger_status,trigger_last_time,trigger_next_time) VALUES (?, ?, NOW(), NOW(), "
                                + "'s7', 'NONE', 'DO_NOTHING', 'ROUND', ?, 'SERIAL_EXECUTION', 0, 0, 'BEAN', 's7', "
                                + "NOW(), 0, 0, 0)",
                        groupId, handler, handler);
            }
            try (var admin = new GenericContainer<>("xuxueli/xxl-job-admin:3.4.2")
                    .withCreateContainerCmdModifier(cmd -> cmd.withPlatform("linux/amd64"))
                    .withAccessToHost(true)
                    .withExposedPorts(8080)
                    .withEnv("PARAMS", "--spring.datasource.url=jdbc:mysql://host.testcontainers.internal:"
                            + mysql.getMappedPort(3306) + "/xxl_job"
                            + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                            + "&allowPublicKeyRetrieval=true&useSSL=false"
                            + " --spring.datasource.username=" + mysql.getUsername()
                            + " --spring.datasource.password=" + mysql.getPassword()
                            + " --xxl.job.accessToken=" + TOKEN
                            + " --xxl.job.logretentiondays=1")
                    .waitingFor(Wait.forLogMessage(".*Started XxlJobAdminApplication.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(2)))
                    .withStartupTimeout(Duration.ofMinutes(3))) {
                admin.start();
                String adminBase = detectAdminBase(admin);
                CatalogJobs jobs = new CatalogJobs();
                XxlJobSimpleExecutor execA = executor(adminBase, portA, jobs);
                XxlJobSimpleExecutor execB = executor(adminBase, portB, jobs);
                runs.set(0);
                effects.clear();
                execA.start();
                execB.start();
                try {
                    await(() -> {
                        Integer registered = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM xxl_job_registry WHERE registry_key=?", Integer.class, APP);
                        return registered != null && registered >= 2;
                    }, "两个执行器未都注册到 admin");
                    String cookie = login(adminBase);
                    String addressA = "http://host.testcontainers.internal:" + portA + "/";
                    String addressB = "http://host.testcontainers.internal:" + portB + "/";
                    for (String handler : HANDLERS) {
                        Integer jobId = jdbc.queryForObject(
                                "SELECT id FROM xxl_job_info WHERE executor_handler=?", Integer.class, handler);
                        trigger(adminBase, cookie, jobId, "ENT-1,WH-A", addressA);
                        trigger(adminBase, cookie, jobId, "ENT-1,WH-A", addressB);
                    }
                    await(() -> runs.get() == HANDLERS.size() * 2, "目录任务未全部被重复触发");
                    assertEquals(HANDLERS.size(), effects.size());
                    for (String handler : HANDLERS) {
                        assertEquals(1, effects.get(handler + "|ENT-1,WH-A"));
                    }
                    assertNull(RootContext.getXID());
                    assertNull(GlobalTransactionContext.getCurrent());
                } finally {
                    execA.destroy();
                    execB.destroy();
                }
            }
        }
    }

    public static final class CatalogJobs {
        @XxlJob("tccReservationWatch")
        public void tccReservationWatch() {
            accept("tccReservationWatch");
        }

        @XxlJob("allocationRecoverySweep")
        public void allocationRecoverySweep() {
            accept("allocationRecoverySweep");
        }

        @XxlJob("expiryEligibilitySweep")
        public void expiryEligibilitySweep() {
            accept("expiryEligibilitySweep");
        }

        @XxlJob("serialTransferRecovery")
        public void serialTransferRecovery() {
            accept("serialTransferRecovery");
        }

        @XxlJob("deviceUnknownResultSweep")
        public void deviceUnknownResultSweep() {
            accept("deviceUnknownResultSweep");
        }

        @XxlJob("stockInternalReconcile")
        public void stockInternalReconcile() {
            accept("stockInternalReconcile");
        }

        @XxlJob("externalReconcileExport")
        public void externalReconcileExport() {
            accept("externalReconcileExport");
        }

        @XxlJob("countApplyRecovery")
        public void countApplyRecovery() {
            accept("countApplyRecovery");
        }

        @XxlJob("archivePlanner")
        public void archivePlanner() {
            accept("archivePlanner");
        }

        @XxlJob("jobLeaseRecovery")
        public void jobLeaseRecovery() {
            accept("jobLeaseRecovery");
        }

        private static void accept(String handler) {
            RootContext.unbind();
            if (GlobalTransactionContext.getCurrent() != null) {
                throw new IllegalStateException("XXL_MUST_NOT_HOLD_TCC");
            }
            runs.incrementAndGet();
            String key = handler + "|" + XxlJobHelper.getJobParam();
            effects.putIfAbsent(key, 1);
        }
    }

    private static XxlJobSimpleExecutor executor(String adminBase, int port, CatalogJobs jobs) {
        String address = "http://host.testcontainers.internal:" + port + "/";
        XxlJobSimpleExecutor executor = new XxlJobSimpleExecutor();
        executor.setAdminAddresses(adminBase);
        executor.setAppname(APP);
        executor.setAccessToken(TOKEN);
        executor.setPort(port);
        executor.setAddress(address);
        executor.setLogPath("target/xxl-s7-logs");
        executor.setLogRetentionDays(1);
        executor.setXxlJobBeanList(List.of(jobs));
        return executor;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String detectAdminBase(GenericContainer<?> admin) throws Exception {
        String root = "http://127.0.0.1:" + admin.getMappedPort(8080);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        Exception last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            for (String base : List.of(root, root + "/xxl-job-admin")) {
                try {
                    HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(base + "/"))
                            .GET().timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() < 500) {
                        return base;
                    }
                } catch (Exception error) {
                    last = error;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError("XXL admin HTTP 未就绪", last);
    }

    private static String login(String adminBase) throws Exception {
        var cookies = new CookieManager();
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(adminBase + "/auth/doLogin"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("userName=admin&password=123456&ifRemember=on"))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 400, "XXL 登录失败: " + response.statusCode() + " " + response.body());
        return cookies.getCookieStore().getCookies().stream()
                .map(item -> item.getName() + "=" + item.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElseThrow(() -> new AssertionError("登录未返回 cookie: " + response.body()));
    }

    private static void trigger(String adminBase, String cookie, int jobId, String param, String address) throws Exception {
        String body = "id=" + jobId
                + "&executorParam=" + URLEncoder.encode(param, StandardCharsets.UTF_8)
                + "&addressList=" + URLEncoder.encode(address, StandardCharsets.UTF_8);
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(adminBase + "/jobinfo/trigger"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 400 && response.body() != null && response.body().contains("\"code\":200"),
                "触发失败: " + response.statusCode() + " " + response.body());
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        fail(message + "; runs=" + runs.get() + " effects=" + effects);
    }

    private static Path schemaFile() {
        Path nested = Path.of("..", "deploy", "init", "mysql-apps", "20-xxl-job.sql");
        Path root = Path.of("deploy", "init", "mysql-apps", "20-xxl-job.sql");
        if (Files.exists(nested)) {
            return nested;
        }
        if (Files.exists(root)) {
            return root;
        }
        throw new IllegalStateException("找不到 XXL 官方表结构脚本");
    }

    private static MysqlDataSource source(MySQLContainer mysql) {
        var source = new MysqlDataSource();
        source.setURL(mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?") + "allowMultiQueries=true");
        source.setUser(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        return source;
    }
}
