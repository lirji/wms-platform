package com.lrj.wms.driver.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.DriverFixtures;
import com.lrj.wms.driver.audit.DriverAuditLogger;
import com.lrj.wms.driver.audit.DriverMetrics;
import com.lrj.wms.driver.policy.DriverPolicy;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.protocol.ExecutionStatus;
import com.lrj.wms.driver.registry.DriverRegistry;
import com.lrj.wms.driver.selector.DriverSelector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DriverCapabilityTest {
    @TempDir Path workspace;

    @Test
    void supportedActionPasses() {
        DriverRouter router = router(List.of(DriverAction.READ_REPO, DriverAction.EXECUTE_TESTS));
        ExecutionResult result = router.execute(request(List.of(DriverAction.READ_REPO)));
        assertEquals(ExecutionStatus.SUCCEEDED, result.status());
    }

    @Test
    void unsupportedActionIsRejectedWithDetails() {
        DriverRouter router = router(List.of(DriverAction.READ_REPO));
        ExecutionResult result = router.execute(request(List.of(DriverAction.EXECUTE_TESTS)));
        assertEquals(ExecutionStatus.REJECTED, result.status());
        assertEquals(DriverErrorCodes.CAPABILITY_NOT_SUPPORTED, result.errorCode());
        assertEquals("codex-local", result.details().get("driverId"));
        assertEquals(DriverAction.EXECUTE_TESTS.wire(), result.details().get("requiredAction"));
        assertInstanceOf(List.class, result.details().get("supportedActions"));
        @SuppressWarnings("unchecked")
        List<String> supported = (List<String>) result.details().get("supportedActions");
        assertTrue(supported.contains(DriverAction.READ_REPO.wire()));
        assertTrue(!supported.contains(DriverAction.EXECUTE_TESTS.wire()));
    }

    private DriverRouter router(List<DriverAction> actions) {
        DriverRegistry registry = new DriverRegistry();
        registry.register(
                DriverFixtures.validManifest(
                        "codex-local", com.lrj.wms.driver.protocol.DesiredStatus.ACTIVE, actions),
                DriverFixtures.healthyAdapter());
        registry.activate("codex-local", workspace);
        return new DriverRouter(
                registry,
                new DriverSelector(registry),
                new DriverPolicy(workspace),
                new DriverAuditLogger(),
                new DriverMetrics());
    }

    private ExecutionRequest request(List<DriverAction> actions) {
        return ExecutionRequest.builder()
                .taskId("cap")
                .driverId("codex-local")
                .workingDirectory(workspace)
                .instruction("read README")
                .requiredActions(actions)
                .build();
    }
}
