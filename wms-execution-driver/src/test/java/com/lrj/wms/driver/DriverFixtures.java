package com.lrj.wms.driver;

import com.lrj.wms.driver.adapter.DriverAdapter;
import com.lrj.wms.driver.health.HealthCheckItem;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.manifest.DriverManifest;
import com.lrj.wms.driver.manifest.DriverManifestLoader;
import com.lrj.wms.driver.protocol.DesiredStatus;
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
import java.util.List;
import java.util.stream.Collectors;

public final class DriverFixtures {
    private DriverFixtures() {}

    public static DriverManifest validManifest() {
        return validManifest("codex-local", DesiredStatus.ACTIVE, List.of(DriverAction.values()));
    }

    public static DriverManifest validManifest(
            String driverId, DesiredStatus status, List<DriverAction> actions) {
        String capabilities =
                actions.stream()
                        .map(action -> "\"" + action.wire() + "\"")
                        .collect(Collectors.joining(", ", "[", "]"));
        return new DriverManifestLoader()
                .parse(
                        """
                {
                  "protocol": "execution-driver-manifest/v1",
                  "driver_id": "%s",
                  "display_name": "codex local CLI adapter",
                  "status": "%s",
                  "priority": 100,
                  "adapters": ["native"],
                  "risk_classes": ["NORMAL"],
                  "capabilities": %s,
                  "command": {"executable": "codex", "version_args": ["--version"], "exec_subcommand": "exec"},
                  "timeout": {"default_ms": 5000, "max_ms": 8000}
                }
                """
                                .formatted(driverId, status.wire(), capabilities));
    }

    public static DriverManifest cursorManifest() {
        return new DriverManifestLoader()
                .parse(
                        """
                {
                  "protocol": "execution-driver-manifest/v1",
                  "driver_id": "cursor-local",
                  "display_name": "Cursor Local CLI Driver",
                  "status": "active",
                  "priority": 110,
                  "adapters": ["native"],
                  "risk_classes": ["NORMAL"],
                  "capabilities": ["read_repo", "write_design_docs", "modify_product_code", "modify_tests",
                    "modify_runtime_files", "execute_local_commands", "execute_tests"],
                  "command": {"executable": "agent", "candidates": ["agent", "cursor-agent"],
                    "version_args": ["-v"], "exec_subcommand": ""},
                  "timeout": {"default_ms": 5000, "max_ms": 8000}
                }
                """);
    }

    public static Path stubAgent(Path dir, String mode) throws Exception {
        Path script = dir.resolve("agent");
        String body =
                switch (mode) {
                    case "ok" ->
                            """
                    #!/bin/sh
                    if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
                      echo "2026.09.10-stub"
                      exit 0
                    fi
                    echo "CURSOR_SMOKE_OK name=wms-platform stack=Java Maven modules=wms-execution-driver tests=JUnit"
                    exit 0
                    """;
                    case "tests" ->
                            """
                    #!/bin/sh
                    if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
                      echo "2026.09.10-stub"
                      exit 0
                    fi
                    echo "EXECUTE_TESTS_OK DriverAuditLoggerTest"
                    exit 0
                    """;
                    case "fail" ->
                            """
                    #!/bin/sh
                    if [ "$1" = "-v" ]; then
                      echo "2026.09.10-stub"
                      exit 0
                    fi
                    echo "cursor-fail-out"
                    echo "cursor-fail-err" >&2
                    exit 9
                    """;
                    default -> throw new IllegalArgumentException(mode);
                };
        Files.writeString(script, body);
        script.toFile().setExecutable(true);
        return script;
    }

    public static Path stubCodex(Path dir, String mode) throws Exception {
        Path script = dir.resolve("codex");
        String body =
                switch (mode) {
                    case "ok" ->
                            """
                    #!/bin/sh
                    if [ "$1" = "--version" ]; then
                      echo "codex-cli stub 0.0.1"
                      exit 0
                    fi
                    echo "SMOKE_OK repo=$(basename "$(pwd)")"
                    if [ -f README.md ]; then head -n 1 README.md; fi
                    exit 0
                    """;
                    case "fail" ->
                            """
                    #!/bin/sh
                    if [ "$1" = "--version" ]; then
                      echo "codex-cli stub 0.0.1"
                      exit 0
                    fi
                    echo "boom-stdout"
                    echo "boom-stderr" >&2
                    exit 7
                    """;
                    case "hang" ->
                            """
                    #!/bin/sh
                    if [ "$1" = "--version" ]; then
                      echo "codex-cli stub 0.0.1"
                      exit 0
                    fi
                    sleep 30
                    exit 0
                    """;
                    default -> throw new IllegalArgumentException(mode);
                };
        Files.writeString(script, body);
        script.toFile().setExecutable(true);
        return script;
    }

    public static DriverAdapter healthyAdapter() {
        return new DriverAdapter() {
            @Override
            public String kind() {
                return "native";
            }

            @Override
            public HealthReport health(RegisteredDriver driver, Path workspace) {
                return new HealthReport(
                        driver.driverId(),
                        RuntimeStatus.ACTIVE,
                        true,
                        List.of(new HealthCheckItem("adapter_initialized", true, "ok")),
                        Instant.now());
            }

            @Override
            public ExecutionResult execute(RegisteredDriver driver, ExecutionRequest request) {
                Instant now = Instant.now();
                return new ExecutionResult(
                        request.requestId(),
                        request.taskId(),
                        driver.driverId(),
                        ExecutionStatus.SUCCEEDED,
                        0,
                        "ok",
                        "",
                        now,
                        now,
                        Duration.ZERO,
                        null,
                        null,
                        List.of(),
                        java.util.Map.of());
            }
        };
    }
}
