package com.lrj.wms.driver.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProcessSpec(
        List<String> command,
        Path workingDirectory,
        Duration timeout,
        Map<String, String> environment) {

    public ProcessSpec {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command不能为空");
        }
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout必须为正");
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
