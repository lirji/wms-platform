package com.lrj.wms.driver.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.wms.driver.DriverFixtures;
import com.lrj.wms.driver.adapter.DriverAdapter;
import com.lrj.wms.driver.health.HealthCheckItem;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.protocol.DesiredStatus;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.protocol.RiskClass;
import com.lrj.wms.driver.protocol.RuntimeStatus;
import com.lrj.wms.driver.registry.DriverRegistry;
import com.lrj.wms.driver.registry.RegisteredDriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

class DriverSelectorTest {
    @TempDir Path workspace;

    @Test
    void activeCapabilityMatchIsSelected() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(DriverFixtures.validManifest(), DriverFixtures.healthyAdapter());
        registry.activate("codex-local", workspace);
        var selected =
                new DriverSelector(registry)
                        .find(
                                new SelectionQuery(
                                        List.of(DriverAction.READ_REPO),
                                        RiskClass.NORMAL,
                                        null,
                                        "local"));
        assertTrue(selected.isPresent());
        assertEquals("codex-local", selected.get().driverId());
    }

    @Test
    void unavailableIsNotSelected() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(DriverFixtures.validManifest(), unavailableAdapter());
        registry.activate("codex-local", workspace);
        assertTrue(
                new DriverSelector(registry)
                        .find(query(List.of(DriverAction.READ_REPO), RiskClass.NORMAL))
                        .isEmpty());
    }

    @Test
    void capabilityMismatchIsNotSelected() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(
                DriverFixtures.validManifest(
                        "codex-local", DesiredStatus.ACTIVE, List.of(DriverAction.READ_REPO)),
                DriverFixtures.healthyAdapter());
        registry.activate("codex-local", workspace);
        assertTrue(
                new DriverSelector(registry)
                        .find(query(List.of(DriverAction.EXECUTE_TESTS), RiskClass.NORMAL))
                        .isEmpty());
    }

    @Test
    void riskMismatchIsNotSelected() {
        DriverRegistry registry = new DriverRegistry();
        registry.register(DriverFixtures.validManifest(), DriverFixtures.healthyAdapter());
        registry.activate("codex-local", workspace);
        // 当前白名单只有 NORMAL；用空 risk 集合的 Driver 模拟不匹配。
        DriverRegistry other = new DriverRegistry();
        other.register(
                new com.lrj.wms.driver.manifest.DriverManifest(
                        "execution-driver-manifest/v1",
                        "codex-local",
                        "x",
                        DesiredStatus.ACTIVE,
                        1,
                        List.of("native"),
                        List.of(),
                        List.of(DriverAction.READ_REPO),
                        DriverFixtures.validManifest().command(),
                        DriverFixtures.validManifest().timeout()),
                DriverFixtures.healthyAdapter());
        other.activate("codex-local", workspace);
        assertTrue(
                new DriverSelector(other)
                        .find(query(List.of(DriverAction.READ_REPO), RiskClass.NORMAL))
                        .isEmpty());
    }

    private static SelectionQuery query(List<DriverAction> actions, RiskClass risk) {
        return new SelectionQuery(actions, risk, null, "local");
    }

    private static DriverAdapter unavailableAdapter() {
        return new DriverAdapter() {
            @Override
            public String kind() {
                return "native";
            }

            @Override
            public HealthReport health(RegisteredDriver driver, Path workspace) {
                return new HealthReport(
                        driver.driverId(),
                        RuntimeStatus.UNAVAILABLE,
                        false,
                        List.of(new HealthCheckItem("codex_cli_exists", false, "missing")),
                        Instant.now());
            }

            @Override
            public ExecutionResult execute(RegisteredDriver driver, ExecutionRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
