package com.lrj.wms.driver.protocol;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 业务层只构造此请求，禁止直接拼 Cursor/Codex shell。 */
public record ExecutionRequest(
        String requestId,
        String taskId,
        String driverId,
        Path workingDirectory,
        String instruction,
        List<DriverAction> requiredActions,
        RiskClass riskClass,
        Duration timeout,
        Map<String, String> environment,
        Map<String, String> metadata) {

    public ExecutionRequest {
        requestId = blankToGenerated(requestId);
        taskId = requireText(taskId, "taskId");
        instruction = requireText(instruction, "instruction");
        requiredActions =
                requiredActions == null || requiredActions.isEmpty()
                        ? List.of(DriverAction.READ_REPO)
                        : List.copyOf(requiredActions);
        riskClass = riskClass == null ? RiskClass.NORMAL : riskClass;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        Objects.requireNonNull(workingDirectory, "workingDirectory");
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String blankToGenerated(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value.trim();
    }

    public static final class Builder {
        private String requestId;
        private String taskId = "task";
        private String driverId;
        private Path workingDirectory;
        private String instruction;
        private List<DriverAction> requiredActions = List.of(DriverAction.READ_REPO);
        private RiskClass riskClass = RiskClass.NORMAL;
        private Duration timeout;
        private Map<String, String> environment = Map.of();
        private Map<String, String> metadata = Map.of();

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder driverId(String driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        public Builder requiredActions(List<DriverAction> requiredActions) {
            this.requiredActions = requiredActions;
            return this;
        }

        public Builder riskClass(RiskClass riskClass) {
            this.riskClass = riskClass;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ExecutionRequest build() {
            return new ExecutionRequest(
                    requestId,
                    taskId,
                    driverId,
                    workingDirectory,
                    instruction,
                    requiredActions,
                    riskClass,
                    timeout,
                    environment,
                    metadata);
        }
    }
}
