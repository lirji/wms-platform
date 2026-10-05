package com.lrj.wms.driver.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.DriverFixtures;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.RegisteredDriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DriverPolicyTest {
    @TempDir Path workspace;

    @Test
    void rejectsWorkspaceEscapeAndDestructiveCommands() {
        RegisteredDriver driver =
                new RegisteredDriver(
                        DriverFixtures.validManifest(),
                        DriverFixtures.healthyAdapter(),
                        RuntimeStatus.ACTIVE);
        driver.mark(
                RuntimeStatus.ACTIVE, DriverFixtures.healthyAdapter().health(driver, workspace));
        DriverPolicy policy = new DriverPolicy(workspace);
        DriverException escape =
                assertThrows(
                        DriverException.class,
                        () ->
                                policy.assertExecutable(
                                        driver,
                                        ExecutionRequest.builder()
                                                .taskId("t")
                                                .workingDirectory(workspace.getRoot())
                                                .instruction("read")
                                                .requiredActions(List.of(DriverAction.READ_REPO))
                                                .build()));
        assertEquals(DriverErrorCodes.POLICY_DENIED, escape.code());
        DriverException destructive =
                assertThrows(
                        DriverException.class,
                        () ->
                                policy.assertExecutable(
                                        driver,
                                        ExecutionRequest.builder()
                                                .taskId("t")
                                                .workingDirectory(workspace)
                                                .instruction("git reset --hard")
                                                .requiredActions(
                                                        List.of(
                                                                DriverAction
                                                                        .EXECUTE_LOCAL_COMMANDS))
                                                .build()));
        assertEquals(DriverErrorCodes.POLICY_DENIED, destructive.code());
    }
}
