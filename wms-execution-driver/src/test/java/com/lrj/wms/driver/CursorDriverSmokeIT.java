package com.lrj.wms.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.process.LocalProcessExecutor;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionStatus;
import com.lrj.wms.driver.protocol.RiskClass;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.selector.SelectionQuery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Selector → cursor-local → native adapter → 进程 → ExecutionResult，不绕过 Driver。 */
class CursorDriverSmokeIT {
    @TempDir Path workspace;

    @Test
    void readRepoGoesThroughSelectorAndCursorAdapter() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# wms-platform");
        Path stub = DriverFixtures.stubAgent(workspace, "ok");
        DriverRuntime runtime =
                new DriverRuntime(
                        workspace,
                        List.of(DriverFixtures.cursorManifest()),
                        new LocalProcessExecutor(),
                        stub);
        assertEquals(RuntimeStatus.ACTIVE, runtime.status("cursor-local").runtimeStatus());
        assertTrue(
                runtime.select(
                                new SelectionQuery(
                                        List.of(DriverAction.READ_REPO),
                                        RiskClass.NORMAL,
                                        "cursor-local",
                                        "local"))
                        .isPresent());
        var result =
                runtime.execute(
                        ExecutionRequest.builder()
                                .taskId("cursor-smoke")
                                .metadata(java.util.Map.of("preferredDriver", "cursor-local"))
                                .workingDirectory(workspace)
                                .instruction("读取仓库，禁止改文件")
                                .requiredActions(List.of(DriverAction.READ_REPO))
                                .riskClass(RiskClass.NORMAL)
                                .timeout(Duration.ofSeconds(5))
                                .build());
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
        assertEquals("cursor-local", result.driverId());
        assertTrue(result.stdout().contains("CURSOR_SMOKE_OK"));
    }

    @Test
    void executeTestsGoesThroughCursorAdapter() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# wms-platform");
        Path stub = DriverFixtures.stubAgent(workspace, "tests");
        DriverRuntime runtime =
                new DriverRuntime(
                        workspace,
                        List.of(DriverFixtures.cursorManifest()),
                        new LocalProcessExecutor(),
                        stub);
        var result =
                runtime.execute(
                        ExecutionRequest.builder()
                                .taskId("cursor-tests")
                                .driverId("cursor-local")
                                .workingDirectory(workspace)
                                .instruction("运行 DriverAuditLoggerTest")
                                .requiredActions(List.of(DriverAction.EXECUTE_TESTS))
                                .riskClass(RiskClass.NORMAL)
                                .timeout(Duration.ofSeconds(5))
                                .build());
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
        assertTrue(result.stdout().contains("EXECUTE_TESTS_OK"));
        List<String> command =
                ((com.lrj.wms.driver.adapter.NativeCursorAdapter)
                                runtime.status("cursor-local").adapter())
                        .commandLine(
                                runtime.status("cursor-local"),
                                ExecutionRequest.builder()
                                        .taskId("t")
                                        .workingDirectory(workspace)
                                        .instruction("run tests")
                                        .requiredActions(List.of(DriverAction.EXECUTE_TESTS))
                                        .build(),
                                stub);
        assertTrue(!command.contains("ask"));
        assertTrue(command.contains("-p"));
    }
}
