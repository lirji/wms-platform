package com.lrj.wms.driver.health;

import com.lrj.wms.driver.protocol.RuntimeStatus;

import java.time.Instant;
import java.util.List;

public record HealthReport(
        String driverId,
        RuntimeStatus status,
        boolean healthy,
        List<HealthCheckItem> checks,
        Instant checkedAt) {

    public HealthReport {
        checks = checks == null ? List.of() : List.copyOf(checks);
        checkedAt = checkedAt == null ? Instant.now() : checkedAt;
    }
}
