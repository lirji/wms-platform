package com.lrj.wms.driver.registry;

import com.lrj.wms.driver.adapter.DriverAdapter;
import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.manifest.DriverManifest;
import com.lrj.wms.driver.protocol.RuntimeStatus;

import java.util.Objects;

public final class RegisteredDriver {
    private final DriverManifest manifest;
    private final DriverAdapter adapter;
    private volatile RuntimeStatus runtimeStatus;
    private volatile HealthReport lastHealth;

    public RegisteredDriver(
            DriverManifest manifest, DriverAdapter adapter, RuntimeStatus runtimeStatus) {
        this.manifest = Objects.requireNonNull(manifest);
        this.adapter = adapter;
        this.runtimeStatus = runtimeStatus == null ? RuntimeStatus.DECLARED : runtimeStatus;
    }

    public String driverId() {
        return manifest.driverId();
    }

    public DriverManifest manifest() {
        return manifest;
    }

    public DriverAdapter adapter() {
        return adapter;
    }

    public RuntimeStatus runtimeStatus() {
        return runtimeStatus;
    }

    public boolean healthy() {
        return lastHealth != null && lastHealth.healthy() && runtimeStatus == RuntimeStatus.ACTIVE;
    }

    public HealthReport lastHealth() {
        return lastHealth;
    }

    public void mark(RuntimeStatus status, HealthReport health) {
        this.runtimeStatus = status;
        this.lastHealth = health;
    }
}
