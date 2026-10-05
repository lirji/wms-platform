package com.lrj.wms.driver.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExecutionResult(
        String requestId,
        String taskId,
        String driverId,
        ExecutionStatus status,
        Integer exitCode,
        String stdout,
        String stderr,
        Instant startedAt,
        Instant finishedAt,
        Duration duration,
        String errorCode,
        String errorMessage,
        List<String> artifacts,
        Map<String, Object> details) {

    public ExecutionResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        details = details == null ? Map.of() : Map.copyOf(details);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        Objects.requireNonNull(status, "status");
    }

    public boolean succeeded() {
        return status == ExecutionStatus.SUCCEEDED;
    }

    public static ExecutionResult rejected(
            ExecutionRequest request,
            String driverId,
            String errorCode,
            String errorMessage,
            Map<String, Object> details) {
        Instant now = Instant.now();
        return new ExecutionResult(
                request.requestId(),
                request.taskId(),
                driverId,
                ExecutionStatus.REJECTED,
                null,
                "",
                "",
                now,
                now,
                Duration.ZERO,
                errorCode,
                errorMessage,
                List.of(),
                details);
    }
}
