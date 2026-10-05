package com.lrj.wms.driver.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.DriverFixtures;
import com.lrj.wms.driver.health.CursorLocalHealthCheck;
import com.lrj.wms.driver.process.LocalProcessExecutor;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionStatus;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.RegisteredDriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class NativeCursorAdapterTest {
    @TempDir Path workspace;

    @Test
    void healthPassesWhenCliAndWorkspaceExist() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# wms-platform");
        Path stub = DriverFixtures.stubAgent(workspace, "ok");
        NativeCursorAdapter adapter = new NativeCursorAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.cursorManifest(), adapter, RuntimeStatus.REGISTERED);
        var health = new CursorLocalHealthCheck(adapter).check(driver, workspace);
        assertTrue(health.healthy());
        assertEquals(RuntimeStatus.ACTIVE, health.status());
        assertTrue(
                health.checks().stream()
                        .anyMatch(
                                item -> "cursor_cli_version".equals(item.name()) && item.passed()));
    }

    @Test
    void healthFailsWhenCliMissing() {
        NativeCursorAdapter adapter =
                new NativeCursorAdapter(
                        new LocalProcessExecutor(), workspace.resolve("missing-agent"));
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.cursorManifest(), adapter, RuntimeStatus.REGISTERED);
        var health = adapter.health(driver, workspace);
        assertFalse(health.healthy());
        assertEquals(RuntimeStatus.UNAVAILABLE, health.status());
    }

    @Test
    void healthFailsWhenWorkspaceMissing() throws Exception {
        Path stub = DriverFixtures.stubAgent(workspace, "ok");
        NativeCursorAdapter adapter = new NativeCursorAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.cursorManifest(), adapter, RuntimeStatus.REGISTERED);
        var health = adapter.health(driver, workspace.resolve("no-such-dir"));
        assertFalse(health.healthy());
        assertTrue(
                health.checks().stream()
                        .anyMatch(
                                item -> "workspace_exists".equals(item.name()) && !item.passed()));
    }

    @Test
    void commandLineUsesAskModeForReadRepo() throws Exception {
        Path stub = DriverFixtures.stubAgent(workspace, "ok");
        NativeCursorAdapter adapter = new NativeCursorAdapter(new LocalProcessExecutor(), stub);
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.cursorManifest(), adapter, RuntimeStatus.ACTIVE);
        List<String> command =
                adapter.commandLine(
                        driver,
                        ExecutionRequest.builder()
                                .taskId("t")
                                .workingDirectory(workspace)
                                .instruction("summarize repo")
                                .requiredActions(List.of(DriverAction.READ_REPO))
                                .build(),
                        stub);
        assertEquals(stub.toString(), command.getFirst());
        assertTrue(command.contains("-p"));
        assertTrue(command.contains("ask"));
        assertTrue(command.contains("summarize repo"));
    }

    @Test
    void missingCliExecuteReturnsCursorCliNotFound() {
        NativeCursorAdapter adapter =
                new NativeCursorAdapter(
                        new LocalProcessExecutor(), workspace.resolve("missing-agent"));
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.cursorManifest(), adapter, RuntimeStatus.ACTIVE);
        var result =
                adapter.execute(
                        driver,
                        ExecutionRequest.builder()
                                .taskId("t")
                                .workingDirectory(workspace)
                                .instruction("read")
                                .build());
        assertEquals(ExecutionStatus.FAILED, result.status());
        assertEquals(DriverErrorCodes.CURSOR_CLI_NOT_FOUND, result.errorCode());
    }
}
