package com.lrj.wms.runtime.messaging;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;
import org.slf4j.LoggerFactory;

/** 单线程固定延迟，不堆积周期任务；每轮应用回调自身必须遵守批量预算。 */
public final class MessageWorker implements SmartLifecycle {
    private final String name;
    private final Runnable pulse;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile boolean lastPulseSucceeded;

    public MessageWorker(String name, Runnable pulse) { this.name = name; this.pulse = pulse; }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(name).factory());
        executor.scheduleWithFixedDelay(() -> {
            lastPulseSucceeded = false;
            try { pulse.run(); lastPulseSucceeded = true; }
            catch (RuntimeException unavailable) {
                lastPulseSucceeded = false;
                LoggerFactory.getLogger(getClass()).warn("消息任务暂不可用，等待下一轮恢复，worker={}", name);
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }
    public boolean lastPulseSucceeded() { return lastPulseSucceeded; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 90; }
    @Override public synchronized void stop() {
        running = false;
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
