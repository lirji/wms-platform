package com.lrj.wms.driver.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class LocalProcessExecutorTest {
    private final LocalProcessExecutor executor = new LocalProcessExecutor();

    @TempDir Path workspace;

    @Test
    void successExitZeroAndStdout() {
        ProcessOutcome outcome =
                executor.run(
                        new ProcessSpec(
                                List.of("sh", "-c", "echo hello-out"),
                                workspace,
                                Duration.ofSeconds(5),
                                java.util.Map.of()));
        assertEquals(0, outcome.exitCode());
        assertTrue(outcome.stdout().contains("hello-out"));
        assertTrue(!outcome.timedOut());
    }

    @Test
    void failurePropagatesExitCodeAndStderr() {
        ProcessOutcome outcome =
                executor.run(
                        new ProcessSpec(
                                List.of("sh", "-c", "echo err-out >&2; exit 3"),
                                workspace,
                                Duration.ofSeconds(5),
                                java.util.Map.of()));
        assertEquals(3, outcome.exitCode());
        assertTrue(outcome.stderr().contains("err-out"));
    }

    @Test
    void timeoutKillsProcess() {
        ProcessOutcome outcome =
                executor.run(
                        new ProcessSpec(
                                List.of("sh", "-c", "sleep 20"),
                                workspace,
                                Duration.ofMillis(300),
                                java.util.Map.of()));
        assertTrue(outcome.timedOut());
        assertEquals(124, outcome.exitCode());
    }

    @Test
    void stdinIsClosedSoCommandsDoNotWaitForPipe() {
        ProcessOutcome outcome =
                executor.run(
                        new ProcessSpec(
                                List.of(
                                        "sh",
                                        "-c",
                                        "if read line; then echo got; else echo closed; fi"),
                                workspace,
                                Duration.ofSeconds(3),
                                java.util.Map.of()));
        assertEquals(0, outcome.exitCode());
        assertTrue(outcome.stdout().contains("closed"));
        assertTrue(!outcome.timedOut());
    }
}
