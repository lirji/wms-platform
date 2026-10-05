package com.lrj.wms.driver.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.DriverFixtures;
import com.lrj.wms.driver.process.LocalProcessExecutor;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionStatus;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.RegisteredDriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class NativeCodexAdapterTest {
    @TempDir Path workspace;

    @Test
    void healthCheckAndCommandConstruction() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# wms-platform");
        Path stub = DriverFixtures.stubCodex(workspace, "ok");
        NativeCodexAdapter adapter = new NativeCodexAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.validManifest(), adapter, RuntimeStatus.REGISTERED);
        var health = adapter.health(driver, workspace);
        assertTrue(health.healthy());
        assertEquals(RuntimeStatus.ACTIVE, health.status());
        ExecutionRequest request =
                ExecutionRequest.builder()
                        .taskId("t")
                        .workingDirectory(workspace)
                        .instruction("summarize README")
                        .requiredActions(List.of(DriverAction.READ_REPO))
                        .build();
        List<String> command = adapter.commandLine(driver, request, stub);
        assertEquals(stub.toString(), command.getFirst());
        assertEquals("exec", command.get(1));
        assertTrue(command.contains("read-only"));
        assertTrue(command.contains("--ignore-user-config"));
        assertTrue(command.contains(workspace.toAbsolutePath().toString()));
        assertTrue(command.contains("summarize README"));
    }

    @Test
    void successfulExecutionPropagatesStdout() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# hello");
        Path stub = DriverFixtures.stubCodex(workspace, "ok");
        NativeCodexAdapter adapter = new NativeCodexAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(DriverFixtures.validManifest(), adapter, RuntimeStatus.ACTIVE);
        var result = adapter.execute(driver, readRequest());
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("SMOKE_OK"));
    }

    @Test
    void failedExecutionPropagatesExitCodeAndStderr() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# hello");
        Path stub = DriverFixtures.stubCodex(workspace, "fail");
        NativeCodexAdapter adapter = new NativeCodexAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(DriverFixtures.validManifest(), adapter, RuntimeStatus.ACTIVE);
        var result = adapter.execute(driver, readRequest());
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(7, result.exitCode());
        assertTrue(result.stderr().contains("boom-stderr"));
        assertTrue(result.stdout().contains("boom-stdout"));
    }

    @Test
    void timeoutTerminatesProcess() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# hello");
        Path stub = DriverFixtures.stubCodex(workspace, "hang");
        NativeCodexAdapter adapter = new NativeCodexAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(DriverFixtures.validManifest(), adapter, RuntimeStatus.ACTIVE);
        var result =
                adapter.execute(
                        driver,
                        ExecutionRequest.builder()
                                .taskId("t")
                                .workingDirectory(workspace)
                                .instruction("hang")
                                .timeout(Duration.ofMillis(400))
                                .build());
        assertEquals(ExecutionStatus.TIMEOUT, result.status());
        assertFalse(result.succeeded());
    }

    private ExecutionRequest readRequest() {
        return ExecutionRequest.builder()
                .taskId("t")
                .workingDirectory(workspace)
                .instruction("read README")
                .requiredActions(List.of(DriverAction.READ_REPO))
                .build();
    }
}
