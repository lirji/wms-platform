package com.lrj.wms.driver.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** 统一进程执行：超时必须杀进程树，避免 Codex CLI 卡死占用任务。 */
public final class LocalProcessExecutor implements ProcessExecutor {
    private static final int OUTPUT_LIMIT = 512 * 1024;

    @Override
    public ProcessOutcome run(ProcessSpec spec) {
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        builder.directory(spec.workingDirectory().toFile());
        builder.redirectErrorStream(false);
        if (!spec.environment().isEmpty()) {
            builder.environment().putAll(spec.environment());
        }
        Instant started = Instant.now();
        Process process;
        try {
            process = builder.start();
            // Codex exec 在 stdin 仍打开时会等待管道输入；关闭后才按 argv 中的 prompt 执行。
            process.getOutputStream().close();
        } catch (IOException error) {
            return new ProcessOutcome(
                    127, "", error.getMessage(), Duration.between(started, Instant.now()), false);
        }
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        stdout.start();
        stderr.start();
        boolean finished;
        try {
            finished = process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminate(process);
            return new ProcessOutcome(
                    143,
                    stdout.text(),
                    stderr.text(),
                    Duration.between(started, Instant.now()),
                    true);
        }
        if (!finished) {
            terminate(process);
            joinQuietly(stdout);
            joinQuietly(stderr);
            return new ProcessOutcome(
                    124,
                    stdout.text(),
                    stderr.text() + "\nprocess terminated after timeout",
                    Duration.between(started, Instant.now()),
                    true);
        }
        joinQuietly(stdout);
        joinQuietly(stderr);
        return new ProcessOutcome(
                process.exitValue(),
                stdout.text(),
                stderr.text(),
                Duration.between(started, Instant.now()),
                false);
    }

    private static void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    int remaining = OUTPUT_LIMIT - buffer.size();
                    if (remaining <= 0) {
                        continue;
                    }
                    buffer.write(chunk, 0, Math.min(read, remaining));
                }
            } catch (IOException ignored) {
                // 进程被杀死时流关闭是预期行为，不能把截断当成功。
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
