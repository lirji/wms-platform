package com.lrj.wms.driver.audit;

import java.util.concurrent.atomic.AtomicLong;

public final class DriverMetrics {
    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();

    public void markSucceeded() {
        executions.incrementAndGet();
        succeeded.incrementAndGet();
    }

    public void markFailed() {
        executions.incrementAndGet();
        failed.incrementAndGet();
    }

    public void markRejected() {
        executions.incrementAndGet();
        rejected.incrementAndGet();
    }

    public void markTimeout() {
        executions.incrementAndGet();
        timedOut.incrementAndGet();
    }

    public long executions() {
        return executions.get();
    }

    public long succeeded() {
        return succeeded.get();
    }

    public long failed() {
        return failed.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long timedOut() {
        return timedOut.get();
    }
}
