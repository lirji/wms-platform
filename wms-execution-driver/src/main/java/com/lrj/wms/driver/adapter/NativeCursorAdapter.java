package com.lrj.wms.driver.adapter;

import com.lrj.wms.driver.health.HealthCheckItem;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.process.ProcessExecutor;
import com.lrj.wms.driver.process.ProcessOutcome;
import com.lrj.wms.driver.process.ProcessSpec;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.protocol.ExecutionStatus;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.RegisteredDriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把平台执行请求转成 Cursor Agent CLI。Cursor 特有参数只留在本适配器。
 */
public final class NativeCursorAdapter implements DriverAdapter {
    public static final String KIND = NativeAdapterBinder.KIND;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(10);
    private final ProcessExecutor processes;
    private final Path executableOverride;

    public NativeCursorAdapter(ProcessExecutor processes) {
        this(processes, null);
    }

    public NativeCursorAdapter(ProcessExecutor processes, Path executableOverride) {
        this.processes = processes;
        this.executableOverride = executableOverride;
    }

    @Override
    public String kind() {
        return KIND;
    }

    public List<String> commandLine(
            RegisteredDriver driver, ExecutionRequest request, Path executable) {
        List<String> argv = new ArrayList<>();
        argv.add(executable.toString());
        argv.add("-p");
        argv.add("--output-format");
        argv.add("text");
        argv.add("--sandbox");
        argv.add("enabled");
        if (readOnly(request)) {
            argv.add("--mode");
            argv.add("ask");
        }
        argv.add(request.instruction());
        return List.copyOf(argv);
    }

    public Optional<Path> resolveExecutable(RegisteredDriver driver) {
        if (executableOverride != null) {
            return Optional.of(executableOverride);
        }
        List<String> names =
                ExecutableResolver.names(
                        driver.manifest().command().executable(),
                        driver.manifest().command().candidates());
        return ExecutableResolver.resolve(
                names, ExecutableResolver.WMS_CURSOR_CLI_ENV, ExecutableResolver.CURSOR_CLI_ENV);
    }

    @Override
    public HealthReport health(RegisteredDriver driver, Path workspace) {
        Instant checkedAt = Instant.now();
        List<HealthCheckItem> checks = new ArrayList<>();
        Optional<Path> executable = resolveExecutable(driver);
        boolean exists =
                executable.isPresent()
                        && Files.isExecutable(executable.get())
                        && Files.isRegularFile(executable.get());
        boolean workspaceExists = workspace != null && Files.isDirectory(workspace);
        checks.add(
                new HealthCheckItem(
                        "cursor_cli_exists",
                        exists,
                        exists
                                ? executable.get().toString()
                                : "PATH/CURSOR_CLI 未找到 "
                                        + driver.manifest().command().executable()));
        boolean runnable = false;
        String versionText = "";
        if (exists) {
            List<String> version = new ArrayList<>();
            version.add(executable.get().toString());
            version.addAll(driver.manifest().command().versionArgs());
            ProcessOutcome outcome =
                    processes.run(
                            new ProcessSpec(
                                    version,
                                    workspaceExists
                                            ? workspace
                                            : Path.of(System.getProperty("java.io.tmpdir")),
                                    HEALTH_TIMEOUT,
                                    java.util.Map.of()));
            versionText = (outcome.stdout() + " " + outcome.stderr()).trim();
            runnable = !outcome.timedOut() && outcome.exitCode() == 0 && !versionText.isBlank();
            checks.add(
                    new HealthCheckItem(
                            "cursor_cli_runnable",
                            runnable,
                            runnable
                                    ? "可执行"
                                    : "version 失败 exit="
                                            + outcome.exitCode()
                                            + " "
                                            + outcome.stderr()));
            checks.add(
                    new HealthCheckItem(
                            "cursor_cli_version", runnable, runnable ? versionText : "无法获取版本"));
        } else {
            checks.add(new HealthCheckItem("cursor_cli_runnable", false, "可执行文件不存在，跳过 -v"));
            checks.add(new HealthCheckItem("cursor_cli_version", false, "CLI 不存在"));
        }
        checks.add(
                new HealthCheckItem(
                        "workspace_exists",
                        workspaceExists,
                        workspaceExists ? workspace.toAbsolutePath().toString() : "workspace 不存在"));
        boolean workspaceReadable = workspaceExists && Files.isReadable(workspace);
        checks.add(
                new HealthCheckItem(
                        "workspace_readable",
                        workspaceReadable,
                        workspaceReadable ? "workspace 可读" : "workspace 不可读"));
        boolean adapterReady = processes != null;
        checks.add(
                new HealthCheckItem(
                        "adapter_initialized", adapterReady, adapterReady ? KIND : "adapter 未初始化"));
        boolean allPassed = checks.stream().allMatch(HealthCheckItem::passed);
        boolean coreReady = exists && runnable && adapterReady && workspaceExists;
        RuntimeStatus status;
        if (allPassed) {
            status = RuntimeStatus.ACTIVE;
        } else if (coreReady) {
            status = RuntimeStatus.DEGRADED;
        } else {
            status = RuntimeStatus.UNAVAILABLE;
        }
        return new HealthReport(driver.driverId(), status, allPassed, checks, checkedAt);
    }

    @Override
    public ExecutionResult execute(RegisteredDriver driver, ExecutionRequest request) {
        Instant started = Instant.now();
        Optional<Path> executable = resolveExecutable(driver);
        if (executable.isEmpty() || !Files.isExecutable(executable.get())) {
            Instant finished = Instant.now();
            return new ExecutionResult(
                    request.requestId(),
                    request.taskId(),
                    driver.driverId(),
                    ExecutionStatus.FAILED,
                    127,
                    "",
                    "Cursor CLI 不存在",
                    started,
                    finished,
                    Duration.between(started, finished),
                    DriverErrorCodes.CURSOR_CLI_NOT_FOUND,
                    "Cursor CLI 不存在",
                    List.of(),
                    java.util.Map.of("commandType", "cursor-agent"));
        }
        Duration timeout = resolveTimeout(driver, request);
        List<String> command = commandLine(driver, request, executable.get());
        ProcessOutcome outcome =
                processes.run(
                        new ProcessSpec(
                                command,
                                request.workingDirectory(),
                                timeout,
                                request.environment()));
        Instant finished = Instant.now();
        if (outcome.timedOut()) {
            return new ExecutionResult(
                    request.requestId(),
                    request.taskId(),
                    driver.driverId(),
                    ExecutionStatus.TIMEOUT,
                    outcome.exitCode(),
                    outcome.stdout(),
                    outcome.stderr(),
                    started,
                    finished,
                    outcome.duration(),
                    DriverErrorCodes.TIMEOUT,
                    "Cursor CLI 超时并已终止进程",
                    List.of(),
                    java.util.Map.of("commandType", "cursor-agent"));
        }
        ExecutionStatus status =
                outcome.exitCode() == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
        return new ExecutionResult(
                request.requestId(),
                request.taskId(),
                driver.driverId(),
                status,
                outcome.exitCode(),
                outcome.stdout(),
                outcome.stderr(),
                started,
                finished,
                outcome.duration(),
                status == ExecutionStatus.SUCCEEDED ? null : DriverErrorCodes.EXECUTION_FAILED,
                status == ExecutionStatus.SUCCEEDED ? null : "Cursor CLI 退出码 " + outcome.exitCode(),
                List.of(),
                java.util.Map.of("commandType", "cursor-agent"));
    }

    static boolean readOnly(ExecutionRequest request) {
        return request.requiredActions().stream()
                .allMatch(action -> action == DriverAction.READ_REPO);
    }

    static Duration resolveTimeout(RegisteredDriver driver, ExecutionRequest request) {
        long max = driver.manifest().timeout().maxMs();
        long requested =
                request.timeout() == null
                        ? driver.manifest().timeout().defaultMs()
                        : request.timeout().toMillis();
        return Duration.ofMillis(Math.min(Math.max(requested, 1), max));
    }
}
