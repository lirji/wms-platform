package com.lrj.wms.probe;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.impl.XxlJobSimpleExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.net.CookieManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
 * 对隔离 XXL admin 做一次真实触发。不是集群/分片验收，执行器不得 Confirm/Cancel。
 */
class XxlAdminTriggerIT {
    private static final String TOKEN = "s0-xxl-probe-token";
    private static final String APP = "wms-s0-xxl";
    private static final String HANDLER = "wmsS0Trigger";
    static final AtomicInteger executed = new AtomicInteger();

    @Test
    void adminTriggerRunsExecutorWithoutTcc() throws Exception {
        int executorPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            executorPort = socket.getLocalPort();
        }
        Testcontainers.exposeHostPorts(executorPort);
        String callback = "http://host.testcontainers.internal:" + executorPort + "/";
        Path schema = schemaFile();
        try (var mysql = new MySQLContainer("mysql:8.4.11").withDatabaseName("xxl_job")
                     .withUsername("wms_xxl").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            Testcontainers.exposeHostPorts(mysql.getMappedPort(3306), executorPort);
            var jdbc = new JdbcTemplate(source(mysql));
            jdbc.execute(Files.readString(schema));
            jdbc.update("INSERT INTO xxl_job_group(app_name,title,address_type,address_list,update_time) VALUES (?,?,1,?,NOW())",
                    APP, "s0-probe", callback);
            Integer groupId = jdbc.queryForObject("SELECT id FROM xxl_job_group WHERE app_name=?", Integer.class, APP);
            jdbc.update("INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,schedule_type,"
                            + "misfire_strategy,executor_route_strategy,executor_handler,executor_block_strategy,"
                            + "executor_timeout,executor_fail_retry_count,glue_type,glue_remark,glue_updatetime,"
                            + "trigger_status,trigger_last_time,trigger_next_time) VALUES (?, 's0 admin trigger', "
                            + "NOW(), NOW(), 'probe', 'NONE', 'DO_NOTHING', 'FIRST', ?, 'SERIAL_EXECUTION', 0, 0, "
                            + "'BEAN', 's0', NOW(), 0, 0, 0)",
                    groupId, HANDLER);
            Integer jobId = jdbc.queryForObject("SELECT id FROM xxl_job_info WHERE executor_handler=?", Integer.class, HANDLER);
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
                var executor = new XxlJobSimpleExecutor();
                executor.setAdminAddresses(adminBase);
                executor.setAppname(APP);
                executor.setAccessToken(TOKEN);
                executor.setPort(executorPort);
                executor.setAddress(callback);
                executor.setLogPath("target/xxl-probe-logs");
                executor.setLogRetentionDays(1);
                executor.setXxlJobBeanList(List.of(new ProbeJob()));
                executed.set(0);
                executor.start();
                try {
                    await(() -> {
                        Integer registered = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM xxl_job_registry WHERE registry_key=?", Integer.class, APP);
                        return registered != null && registered > 0;
                    }, "XXL 执行器未注册到 admin");
                    String cookie = login(adminBase);
                    trigger(adminBase, cookie, jobId, callback);
                    try {
                        await(() -> executed.get() == 1, "XXL admin 未在30秒内回调执行器");
                    } catch (AssertionError timeout) {
                        fail(timeout.getMessage() + "; job log=" + jdbc.queryForList(
                                "SELECT trigger_code,handle_code,trigger_msg,handle_msg FROM xxl_job_log WHERE job_id=?",
                                jobId));
                    }
                    assertNull(RootContext.getXID());
                    assertNull(GlobalTransactionContext.getCurrent());
                    System.out.println("XXL_ADMIN_TRIGGER: official 3.4.2 admin dispatched BEAN handler; executor did not hold TCC");
                } finally {
                    executor.destroy();
                }
            }
        }
    }

    /** 被 admin 调度的探针任务：清理上下文，禁止驱动二阶段。 */
    public static final class ProbeJob {
        @XxlJob(HANDLER)
        public void run() {
            RootContext.unbind();
            if (GlobalTransactionContext.getCurrent() != null) {
                throw new IllegalStateException("XXL_MUST_NOT_HOLD_TCC");
            }
            XxlJobHelper.log("s0 admin trigger");
            executed.incrementAndGet();
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

    private static void trigger(String adminBase, String cookie, int jobId, String address) throws Exception {
        String body = "id=" + jobId + "&executorParam=s0-probe&addressList="
                + URLEncoder.encode(address, StandardCharsets.UTF_8);
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(adminBase + "/jobinfo/trigger"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 400 && response.body() != null && response.body().contains("\"code\":200"),
                "触发失败: " + response.statusCode() + " " + response.body());
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        do {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        fail(message);
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
