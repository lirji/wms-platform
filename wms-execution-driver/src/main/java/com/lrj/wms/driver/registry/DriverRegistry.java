package com.lrj.wms.driver.registry;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.adapter.DriverAdapter;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.manifest.DriverManifest;
import com.lrj.wms.driver.protocol.DesiredStatus;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.RuntimeStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DriverRegistry {
    private final Map<String, RegisteredDriver> drivers = new LinkedHashMap<>();

    public RegisteredDriver register(DriverManifest manifest, DriverAdapter adapter) {
        if (drivers.containsKey(manifest.driverId())) {
            throw new DriverException(
                    DriverErrorCodes.DUPLICATE_ID,
                    "重复的 driverId",
                    Map.of("driverId", manifest.driverId()));
        }
        RuntimeStatus initial =
                manifest.status() == DesiredStatus.DISABLED
                        ? RuntimeStatus.DISABLED
                        : RuntimeStatus.REGISTERED;
        RegisteredDriver driver = new RegisteredDriver(manifest, adapter, initial);
        drivers.put(manifest.driverId(), driver);
        return driver;
    }

    public HealthReport activate(String driverId, Path workspace) {
        RegisteredDriver driver = require(driverId);
        if (driver.manifest().status() == DesiredStatus.DISABLED) {
            HealthReport report =
                    new HealthReport(
                            driverId,
                            RuntimeStatus.DISABLED,
                            false,
                            List.of(),
                            java.time.Instant.now());
            driver.mark(RuntimeStatus.DISABLED, report);
            return report;
        }
        if (driver.adapter() == null) {
            HealthReport report =
                    new HealthReport(
                            driverId,
                            RuntimeStatus.UNAVAILABLE,
                            false,
                            List.of(),
                            java.time.Instant.now());
            driver.mark(RuntimeStatus.UNAVAILABLE, report);
            return report;
        }
        HealthReport report = driver.adapter().health(driver, workspace);
        RuntimeStatus status = report.status();
        if (status == RuntimeStatus.ACTIVE && !report.healthy()) {
            status = RuntimeStatus.UNAVAILABLE;
        }
        driver.mark(status, report);
        return new HealthReport(
                driverId,
                driver.runtimeStatus(),
                driver.healthy(),
                report.checks(),
                report.checkedAt());
    }

    public RegisteredDriver require(String driverId) {
        RegisteredDriver driver = drivers.get(driverId);
        if (driver == null) {
            throw new DriverException(
                    DriverErrorCodes.NOT_FOUND, "Driver 不存在", Map.of("driverId", driverId));
        }
        return driver;
    }

    public Optional<RegisteredDriver> find(String driverId) {
        return Optional.ofNullable(drivers.get(driverId));
    }

    public List<RegisteredDriver> list() {
        return new ArrayList<>(drivers.values());
    }
}
