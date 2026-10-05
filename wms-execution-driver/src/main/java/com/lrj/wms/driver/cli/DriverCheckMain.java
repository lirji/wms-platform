package com.lrj.wms.driver.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.wms.driver.DriverRuntime;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.protocol.RiskClass;
import com.lrj.wms.driver.registry.RegisteredDriver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量入口，避免为 Driver 引入大型 CLI 框架。
 * 用法：list | status &lt;id&gt; | health &lt;id&gt; | capabilities &lt;id&gt; | smoke [id]
 *      | execute-tests [id] | check [id] | &lt;driverId&gt;
 */
public final class DriverCheckMain {
    private static final Set<String> VERBS =
            Set.of("list", "status", "health", "capabilities", "smoke", "execute-tests", "check");
    private static final String DEFAULT_DRIVER = "cursor-local";

    private DriverCheckMain() {}

    public static void main(String[] args) throws Exception {
        Path workspace =
                Path.of(System.getProperty("wms.driver.workspace", System.getProperty("user.dir")));
        DriverRuntime runtime = DriverRuntime.load(workspace);
        String command;
        String driverId;
        if (args.length == 0) {
            command = "check";
            driverId = null;
        } else if (VERBS.contains(args[0])) {
            command = args[0];
            driverId = args.length > 1 ? args[1] : defaultDriver(command);
        } else {
            command = "check";
            driverId = args[0];
        }
        ObjectMapper json =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .enable(SerializationFeature.INDENT_OUTPUT);
        int exit =
                switch (command) {
                    case "list" -> print(json, listPayload(runtime));
                    case "status" ->
                            print(json, statusPayload(runtime.status(requireId(driverId))));
                    case "health" -> print(json, runtime.health(requireId(driverId)));
                    case "capabilities" ->
                            print(
                                    json,
                                    Map.of(
                                            "driverId",
                                            requireId(driverId),
                                            "capabilities",
                                            runtime.capabilities(driverId).stream()
                                                    .map(DriverAction::wire)
                                                    .toList()));
                    case "smoke" -> smoke(json, runtime, requireId(driverId));
                    case "execute-tests" -> executeTests(json, runtime, requireId(driverId));
                    case "check" -> check(json, runtime, driverId);
                    default -> {
                        System.err.println("未知命令: " + command);
                        yield 2;
                    }
                };
        System.exit(exit);
    }

    private static String defaultDriver(String command) {
        return "list".equals(command) || "check".equals(command) ? null : DEFAULT_DRIVER;
    }

    private static String requireId(String driverId) {
        return driverId == null || driverId.isBlank() ? DEFAULT_DRIVER : driverId;
    }

    private static int check(ObjectMapper json, DriverRuntime runtime, String driverId)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspace", runtime.workspace().toString());
        payload.put("drivers", listPayload(runtime));
        List<String> ids =
                driverId == null || driverId.isBlank()
                        ? runtime.list().stream().map(RegisteredDriver::driverId).toList()
                        : List.of(driverId);
        boolean allHealthy = true;
        for (String id : ids) {
            payload.put(id, statusPayload(runtime.status(id)));
            payload.put(id + ".health", runtime.health(id));
            payload.put(
                    id + ".capabilities",
                    runtime.capabilities(id).stream().map(DriverAction::wire).toList());
            allHealthy &= runtime.status(id).healthy();
        }
        payload.put("executable", allHealthy);
        print(json, payload);
        return allHealthy ? 0 : 1;
    }

    private static int smoke(ObjectMapper json, DriverRuntime runtime, String driverId)
            throws Exception {
        ExecutionRequest request =
                ExecutionRequest.builder()
                        .taskId("driver-smoke")
                        .driverId(driverId)
                        .workingDirectory(runtime.workspace())
                        .instruction(
                                """
                        只读取当前仓库，禁止修改任何文件。
                        输出：
                        1. 项目名称
                        2. 技术栈
                        3. 构建工具
                        4. 项目模块
                        5. 测试框架
                        """)
                        .requiredActions(List.of(DriverAction.READ_REPO))
                        .riskClass(RiskClass.NORMAL)
                        .timeout(Duration.ofMinutes(3))
                        .metadata(Map.of("executionMode", "local", "preferredDriver", driverId))
                        .build();
        ExecutionResult result = runtime.execute(request);
        print(json, result);
        return result.succeeded() ? 0 : 1;
    }

    private static int executeTests(ObjectMapper json, DriverRuntime runtime, String driverId)
            throws Exception {
        ExecutionRequest request =
                ExecutionRequest.builder()
                        .taskId("driver-execute-tests")
                        .driverId(driverId)
                        .workingDirectory(runtime.workspace())
                        .instruction(
                                """
                        在当前仓库执行这一组安全、快速的 Maven 单元测试，禁止修改任何源码或配置：
                        mvn -pl wms-execution-driver -Dtest=DriverAuditLoggerTest test
                        只回报测试是否通过。
                        """)
                        .requiredActions(List.of(DriverAction.EXECUTE_TESTS))
                        .riskClass(RiskClass.NORMAL)
                        .timeout(Duration.ofMinutes(4))
                        .metadata(Map.of("executionMode", "local", "preferredDriver", driverId))
                        .build();
        ExecutionResult result = runtime.execute(request);
        print(json, result);
        return result.succeeded() ? 0 : 1;
    }

    private static List<Map<String, Object>> listPayload(DriverRuntime runtime) {
        return runtime.list().stream().map(DriverCheckMain::statusPayload).toList();
    }

    private static Map<String, Object> statusPayload(RegisteredDriver driver) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("driverId", driver.driverId());
        map.put("displayName", driver.manifest().displayName());
        map.put("desiredStatus", driver.manifest().status().wire());
        map.put("runtimeStatus", driver.runtimeStatus().name());
        map.put("healthy", driver.healthy());
        map.put("adapter", driver.adapter() == null ? "" : driver.adapter().kind());
        map.put(
                "capabilities",
                driver.manifest().capabilities().stream().map(DriverAction::wire).toList());
        map.put("riskClasses", driver.manifest().riskClasses().stream().map(Enum::name).toList());
        if (driver.lastHealth() != null) {
            map.put("lastHealth", driver.lastHealth());
        }
        return map;
    }

    private static int print(ObjectMapper json, Object payload) throws Exception {
        System.out.println(json.writeValueAsString(payload));
        return 0;
    }
}
