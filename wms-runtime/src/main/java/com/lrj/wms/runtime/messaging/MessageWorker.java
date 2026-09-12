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
    private record PulseStatus(long generation, boolean succeeded, long completedAt) { }
    private final java.util.concurrent.atomic.AtomicReference<PulseStatus> lastPulse =
            new java.util.concurrent.atomic.AtomicReference<>(new PulseStatus(0, false, 0));
    private long generation;

    public MessageWorker(String name, Runnable pulse) { this.name = name; this.pulse = pulse; }
    @Override public synchronized void start() {
        if (running) return;
        running = true;
        long currentGeneration = ++generation;
        lastPulse.set(new PulseStatus(currentGeneration, false, 0));
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(name).factory());
        executor.scheduleWithFixedDelay(() -> {
            // 本轮开始不抹掉上次完整结果，否则每250ms都会短暂宣告不健康。
            try { pulse.run(); completePulse(currentGeneration, true); }
            catch (RuntimeException unavailable) {
                completePulse(currentGeneration, false);
                LoggerFactory.getLogger(getClass()).warn("消息任务暂不可用，等待下一轮恢复，worker={}", name);
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }
    private void completePulse(long epoch, boolean success) {
        // stop/start已切换代际后，旧执行线程的迟到结果不能污染新实例健康状态。
        lastPulse.updateAndGet(previous -> previous.generation() == epoch
                ? new PulseStatus(epoch, success, System.nanoTime()) : previous);
    }
    /** 保留最近完成结果，同时限定30秒新鲜度，卡死的执行器不能一直宣告健康。 */
    public boolean lastPulseSucceeded() {
        PulseStatus completed = lastPulse.get();
        return running && completed.succeeded() && System.nanoTime() - completed.completedAt() < TimeUnit.SECONDS.toNanos(30);
    }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 90; }
    @Override public synchronized void stop() {
        running = false;
        lastPulse.set(new PulseStatus(++generation, false, 0));
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
