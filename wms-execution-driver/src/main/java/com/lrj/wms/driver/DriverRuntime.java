package com.lrj.wms.driver;

import com.lrj.wms.driver.adapter.DriverAdapter;
import com.lrj.wms.driver.adapter.NativeAdapterBinder;
import com.lrj.wms.driver.audit.DriverAuditLogger;
import com.lrj.wms.driver.audit.DriverMetrics;
import com.lrj.wms.driver.execution.DriverRouter;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.manifest.DriverManifest;
import com.lrj.wms.driver.manifest.DriverManifestLoader;
import com.lrj.wms.driver.policy.DriverPolicy;
import com.lrj.wms.driver.process.LocalProcessExecutor;
import com.lrj.wms.driver.process.ProcessExecutor;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.registry.DriverRegistry;
import com.lrj.wms.driver.registry.RegisteredDriver;
import com.lrj.wms.driver.selector.DriverSelector;
import com.lrj.wms.driver.selector.SelectionQuery;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Driver 生命周期入口：发现 → 校验 → 注册 → 激活 → 选择 → 执行。 */
public final class DriverRuntime {
    private final Path workspace;
    private final DriverRegistry registry;
    private final DriverSelector selector;
    private final DriverRouter router;

    public DriverRuntime(
            Path workspace,
            List<DriverManifest> manifests,
            ProcessExecutor processes,
            Path executableOverride) {
        this(workspace, manifests, processes, overrideMap(manifests, executableOverride));
    }

    public DriverRuntime(
            Path workspace,
            List<DriverManifest> manifests,
            ProcessExecutor processes,
            Map<String, Path> executableOverrides) {
        this.workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        this.registry = new DriverRegistry();
        Map<String, Path> overrides = executableOverrides == null ? Map.of() : executableOverrides;
        for (DriverManifest manifest : manifests) {
            DriverAdapter adapter = NativeAdapterBinder.bind(manifest, processes, overrides);
            registry.register(manifest, adapter);
            registry.activate(manifest.driverId(), this.workspace);
        }
        this.selector = new DriverSelector(registry);
        this.router =
                new DriverRouter(
                        registry,
                        selector,
                        new DriverPolicy(this.workspace),
                        new DriverAuditLogger(),
                        new DriverMetrics());
    }

    private static Map<String, Path> overrideMap(
            List<DriverManifest> manifests, Path executableOverride) {
        if (executableOverride == null || manifests == null || manifests.size() != 1) {
            return Map.of();
        }
        return Map.of(manifests.getFirst().driverId(), executableOverride);
    }

    public static DriverRuntime load(Path workspace) {
        return load(workspace, new LocalProcessExecutor(), Map.of());
    }

    public static DriverRuntime load(
            Path workspace, ProcessExecutor processes, Path executableOverride) {
        List<DriverManifest> manifests = new DriverManifestLoader().loadDefault();
        return new DriverRuntime(workspace, manifests, processes, executableOverride);
    }

    public static DriverRuntime load(
            Path workspace, ProcessExecutor processes, Map<String, Path> executableOverrides) {
        List<DriverManifest> manifests = new DriverManifestLoader().loadDefault();
        return new DriverRuntime(workspace, manifests, processes, executableOverrides);
    }

    public Path workspace() {
        return workspace;
    }

    public DriverRegistry registry() {
        return registry;
    }

    public DriverSelector selector() {
        return selector;
    }

    public List<RegisteredDriver> list() {
        return registry.list();
    }

    public RegisteredDriver status(String driverId) {
        return registry.require(driverId);
    }

    public HealthReport health(String driverId) {
        return registry.activate(driverId, workspace);
    }

    public List<DriverAction> capabilities(String driverId) {
        return registry.require(driverId).manifest().capabilities();
    }

    public Optional<RegisteredDriver> select(SelectionQuery query) {
        return selector.find(query);
    }

    public ExecutionResult execute(ExecutionRequest request) {
        return router.execute(request);
    }

    public DriverRouter router() {
        return router;
    }
}
