package com.lrj.wms.driver.adapter;

import com.lrj.wms.driver.health.HealthReport;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.registry.RegisteredDriver;

import java.nio.file.Path;

public interface DriverAdapter {
    String kind();

    HealthReport health(RegisteredDriver driver, Path workspace);

    ExecutionResult execute(RegisteredDriver driver, ExecutionRequest request);
}
