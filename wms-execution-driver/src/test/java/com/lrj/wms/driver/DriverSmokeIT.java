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

/** 走完整链路：Selector → codex-local → native adapter → 进程执行 → ExecutionResult。 */
class DriverSmokeIT {
    @TempDir Path workspace;

    @Test
    void readRepoGoesThroughSelectorAdapterAndProcess() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# wms-platform smoke");
        Path stub = DriverFixtures.stubCodex(workspace, "ok");
        DriverRuntime runtime =
                new DriverRuntime(
                        workspace,
                        List.of(DriverFixtures.validManifest()),
                        new LocalProcessExecutor(),
                        stub);
        assertEquals(RuntimeStatus.ACTIVE, runtime.status("codex-local").runtimeStatus());
        var selected =
                runtime.select(
                        new SelectionQuery(
                                List.of(DriverAction.READ_REPO),
                                RiskClass.NORMAL,
                                "codex-local",
                                "local"));
        assertTrue(selected.isPresent());
        var result =
                runtime.execute(
                        ExecutionRequest.builder()
                                .taskId("smoke")
                                .workingDirectory(workspace)
                                .instruction("读取仓库名称和 README")
                                .requiredActions(List.of(DriverAction.READ_REPO))
                                .riskClass(RiskClass.NORMAL)
                                .timeout(Duration.ofSeconds(5))
                                .build());
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
        assertEquals("codex-local", result.driverId());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("SMOKE_OK"));
        assertTrue(
                result.stdout().contains("wms-platform smoke")
                        || result.stdout().contains("SMOKE_OK"));
    }
}
