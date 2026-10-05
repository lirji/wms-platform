package com.lrj.wms.driver.process;

import java.time.Duration;

public record ProcessOutcome(
        int exitCode, String stdout, String stderr, Duration duration, boolean timedOut) {
    public ProcessOutcome {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        duration = duration == null ? Duration.ZERO : duration;
    }
}
