package com.lrj.wms.driver.execution;

import com.lrj.wms.driver.DriverException;
import com.lrj.wms.driver.audit.DriverAuditLogger;
import com.lrj.wms.driver.audit.DriverMetrics;
import com.lrj.wms.driver.policy.DriverPolicy;
import com.lrj.wms.driver.protocol.DriverAction;
import com.lrj.wms.driver.protocol.DriverErrorCodes;
import com.lrj.wms.driver.protocol.ExecutionRequest;
import com.lrj.wms.driver.protocol.ExecutionResult;
import com.lrj.wms.driver.protocol.ExecutionStatus;
import com.lrj.wms.driver.registry.DriverRegistry;
import com.lrj.wms.driver.registry.RegisteredDriver;
import com.lrj.wms.driver.selector.DriverSelector;
import com.lrj.wms.driver.selector.SelectionQuery;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DriverRouter {
    private final DriverRegistry registry;
    private final DriverSelector selector;
    private final DriverPolicy policy;
    private final DriverAuditLogger audit;
    private final DriverMetrics metrics;

    public DriverRouter(
            DriverRegistry registry,
            DriverSelector selector,
            DriverPolicy policy,
            DriverAuditLogger audit,
            DriverMetrics metrics) {
        this.registry = registry;
        this.selector = selector;
        this.policy = policy;
        this.audit = audit;
        this.metrics = metrics;
    }

    public ExecutionResult execute(ExecutionRequest request) {
        RegisteredDriver driver;
        try {
            String preferred = request.driverId();
            if (preferred == null || preferred.isBlank()) {
                preferred = request.metadata().get("preferredDriver");
            }
            driver =
                    selector.require(
                            new SelectionQuery(
                                    request.requiredActions(),
                                    request.riskClass(),
                                    preferred,
                                    request.metadata().getOrDefault("executionMode", "local")));
            policy.assertExecutable(driver, request);
        } catch (DriverException error) {
            metrics.markRejected();
            String driverId = request.driverId();
            if (error.details().get("driverId") instanceof String id) {
                driverId = id;
            }
            ExecutionResult rejected =
                    ExecutionResult.rejected(
                            request, driverId, error.code(), error.getMessage(), error.details());
            audit(request, rejected, "reject");
            return rejected;
        }
        ExecutionResult result = driver.adapter().execute(driver, request);
        record(result);
        String executionType =
                String.valueOf(result.details().getOrDefault("commandType", "native-exec"));
        audit(request, result, executionType);
        return result;
    }

    private void record(ExecutionResult result) {
        switch (result.status()) {
            case SUCCEEDED -> metrics.markSucceeded();
            case TIMEOUT -> metrics.markTimeout();
            case REJECTED -> metrics.markRejected();
            default -> metrics.markFailed();
        }
    }

    private void audit(ExecutionRequest request, ExecutionResult result, String executionType) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", request.requestId());
        fields.put("taskId", request.taskId());
        fields.put("driverId", result.driverId() == null ? "" : result.driverId());
        fields.put(
                "requiredActions",
                request.requiredActions().stream().map(DriverAction::wire).toList());
        fields.put("riskClass", request.riskClass().wire());
        fields.put("command/execution type", executionType);
        fields.put("startedAt", result.startedAt());
        fields.put("finishedAt", result.finishedAt());
        fields.put("duration", result.duration());
        fields.put("status", result.status());
        fields.put("exitCode", result.exitCode());
        fields.put("errorCode", result.errorCode());
        request.environment().forEach((key, value) -> fields.put("env." + key, value));
        audit.record(fields);
    }

    public DriverMetrics metrics() {
        return metrics;
    }

    /** 选择层公开错误码，供调用方断言。 */
    public static String selectionFailedCode() {
        return DriverErrorCodes.SELECTION_FAILED;
    }

    public static boolean isRejected(ExecutionResult result) {
        return result.status() == ExecutionStatus.REJECTED;
    }
}
