package com.lrj.wms.driver.adapter;

import com.lrj.wms.driver.health.HealthCheckItem;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.process.ProcessExecutor;
import com.lrj.wms.driver.process.ProcessOutcome;
import com.lrj.wms.driver.process.ProcessSpec;
import com.lrj.wms.driver.protocol.DriverAction;
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
 * 把平台执行请求转成 Codex CLI，Codex 特有参数只留在本适配器。
 */
public final class NativeCodexAdapter implements DriverAdapter {
    public static final String KIND = "native";
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(10);
    private final ProcessExecutor processes;
    private final Path executableOverride;

    public NativeCodexAdapter(ProcessExecutor processes) {
        this(processes, null);
    }

    public NativeCodexAdapter(ProcessExecutor processes, Path executableOverride) {
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
        argv.add(driver.manifest().command().execSubcommand());
        argv.add("--sandbox");
        argv.add(sandboxMode(request));
        argv.add("--cd");
        argv.add(request.workingDirectory().toAbsolutePath().toString());
        argv.add("--skip-git-repo-check");
        argv.add("--ephemeral");
        // 不加载个人 ~/.codex 的 MCP，避免无关 OAuth 失败拖垮平台 Driver。
        argv.add("--ignore-user-config");
        argv.add("--color");
        argv.add("never");
        argv.add(request.instruction());
        return List.copyOf(argv);
    }

    public Optional<Path> resolveExecutable(RegisteredDriver driver) {
        if (executableOverride != null) {
            return Optional.of(executableOverride);
        }
        return ExecutableResolver.resolve(driver.manifest().command().executable());
    }

    @Override
    public HealthReport health(RegisteredDriver driver, Path workspace) {
        Instant checkedAt = Instant.now();
        List<HealthCheckItem> checks = new ArrayList<>();
        Optional<Path> executable = resolveExecutable(driver);
        boolean exists = executable.isPresent() && Files.isExecutable(executable.get());
        checks.add(
                new HealthCheckItem(
                        "codex_cli_exists",
                        exists,
                        exists
                                ? executable.get().toString()
                                : "PATH/CODEX_CLI 未找到 "
                                        + driver.manifest().command().executable()));
        boolean runnable = false;
        if (exists) {
            List<String> version = new ArrayList<>();
            version.add(executable.get().toString());
            version.addAll(driver.manifest().command().versionArgs());
            ProcessOutcome outcome =
                    processes.run(
                            new ProcessSpec(
                                    version, workspace, HEALTH_TIMEOUT, java.util.Map.of()));
            runnable = !outcome.timedOut() && outcome.exitCode() == 0;
            checks.add(
                    new HealthCheckItem(
                            "codex_cli_runnable",
                            runnable,
                            runnable
                                    ? outcome.stdout().trim()
                                    : "version 失败 exit="
                                            + outcome.exitCode()
                                            + " "
                                            + outcome.stderr()));
        } else {
            checks.add(new HealthCheckItem("codex_cli_runnable", false, "可执行文件不存在，跳过 --version"));
        }
        boolean cwdValid = workspace != null && Files.isDirectory(workspace);
        checks.add(
                new HealthCheckItem(
                        "working_directory_valid",
                        cwdValid,
                        cwdValid ? workspace.toAbsolutePath().toString() : "工作目录无效"));
        boolean repoAccessible =
                cwdValid
                        && (Files.exists(workspace.resolve(".git"))
                                || Files.exists(workspace.resolve("README.md"))
                                || Files.isReadable(workspace));
        checks.add(
                new HealthCheckItem(
                        "repository_accessible",
                        repoAccessible,
                        repoAccessible ? "仓库可访问" : "工作目录不可读或不是仓库"));
        boolean adapterReady = processes != null;
        checks.add(
                new HealthCheckItem(
                        "adapter_initialized", adapterReady, adapterReady ? KIND : "adapter 未初始化"));
        boolean allPassed = checks.stream().allMatch(HealthCheckItem::passed);
        boolean coreReady = exists && runnable && adapterReady && cwdValid;
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
        if (executable.isEmpty()) {
            Instant finished = Instant.now();
            return new ExecutionResult(
                    request.requestId(),
                    request.taskId(),
                    driver.driverId(),
                    ExecutionStatus.FAILED,
                    127,
                    "",
                    "Codex CLI 不存在",
                    started,
                    finished,
                    Duration.between(started, finished),
                    "DRIVER_EXECUTION_FAILED",
                    "Codex CLI 不存在",
                    List.of(),
                    java.util.Map.of());
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
                    "DRIVER_TIMEOUT",
                    "Codex CLI 超时并已终止进程",
                    List.of(),
                    java.util.Map.of("commandType", "codex-exec"));
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
                status == ExecutionStatus.SUCCEEDED ? null : "DRIVER_EXECUTION_FAILED",
                status == ExecutionStatus.SUCCEEDED ? null : "Codex CLI 退出码 " + outcome.exitCode(),
                List.of(),
                java.util.Map.of("commandType", "codex-exec"));
    }

    static String sandboxMode(ExecutionRequest request) {
        boolean writes =
                request.requiredActions().stream()
                        .anyMatch(action -> action != DriverAction.READ_REPO);
        return writes ? "workspace-write" : "read-only";
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
