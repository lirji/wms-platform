package com.lrj.wms.driver.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.DriverFixtures;
import com.lrj.wms.driver.adapter.NativeCodexAdapter;
import com.lrj.wms.driver.policy.DriverPolicy;
import com.lrj.wms.driver.process.LocalProcessExecutor;
import com.lrj.wms.driver.protocol.DesiredStatus;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.RiskClass;
import com.lrj.wms.driver.protocol.RuntimeStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class DriverRegistryTest {
    @TempDir Path temp;

    @Test
    void registersAndActivatesHealthyDriver() throws Exception {
        Files.writeString(temp.resolve("README.md"), "# wms-platform");
        Path stub = DriverFixtures.stubCodex(temp, "ok");
        DriverRegistry registry = new DriverRegistry();
        registry.register(
                DriverFixtures.validManifest(),
                new NativeCodexAdapter(new LocalProcessExecutor(), stub));
        var health = registry.activate("codex-local", temp);
        assertEquals(RuntimeStatus.ACTIVE, health.status());
        assertTrue(health.healthy());
        assertEquals(RuntimeStatus.ACTIVE, registry.require("codex-local").runtimeStatus());
    }

    @Test
    void duplicateDriverIdIsRejected() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(DriverFixtures.validManifest(), DriverFixtures.healthyAdapter());
        DriverException error =
                assertThrows(
                        DriverException.class,
                        () ->
                                registry.register(
                                        DriverFixtures.validManifest(),
                                        DriverFixtures.healthyAdapter()));
        assertEquals(DriverErrorCodes.DUPLICATE_ID, error.code());
    }

    @Test
    void disabledDriverCannotExecute(@TempDir Path workspace) {
        DriverRegistry registry = new DriverRegistry();
        registry.register(
                DriverFixtures.validManifest(
                        "codex-local", DesiredStatus.DISABLED, List.of(DriverAction.READ_REPO)),
                DriverFixtures.healthyAdapter());
        registry.activate("codex-local", workspace);
        assertEquals(RuntimeStatus.DISABLED, registry.require("codex-local").runtimeStatus());
        DriverPolicy policy = new DriverPolicy(workspace);
        DriverException error =
                assertThrows(
                        DriverException.class,
                        () ->
                                policy.assertExecutable(
                                        registry.require("codex-local"),
                                        ExecutionRequest.builder()
                                                .taskId("t")
                                                .workingDirectory(workspace)
                                                .instruction("read README")
                                                .requiredActions(List.of(DriverAction.READ_REPO))
                                                .riskClass(RiskClass.NORMAL)
                                                .build()));
        assertEquals(DriverErrorCodes.DISABLED, error.code());
    }

    @Test
    void unhealthyDriverCannotExecute() throws Exception {
        Files.writeString(temp.resolve("README.md"), "# x");
        DriverRegistry registry = new DriverRegistry();
        registry.register(
                DriverFixtures.validManifest(),
                new NativeCodexAdapter(new LocalProcessExecutor(), temp.resolve("missing-codex")));
        registry.activate("codex-local", temp);
        assertEquals(RuntimeStatus.UNAVAILABLE, registry.require("codex-local").runtimeStatus());
        assertFalse(registry.require("codex-local").healthy());
        DriverPolicy policy = new DriverPolicy(temp);
        DriverException error =
                assertThrows(
                        DriverException.class,
                        () ->
                                policy.assertExecutable(
                                        registry.require("codex-local"),
                                        ExecutionRequest.builder()
                                                .taskId("t")
                                                .workingDirectory(temp)
                                                .instruction("read README")
                                                .requiredActions(List.of(DriverAction.READ_REPO))
                                                .build()));
        assertEquals(DriverErrorCodes.UNAVAILABLE, error.code());
    }
}
