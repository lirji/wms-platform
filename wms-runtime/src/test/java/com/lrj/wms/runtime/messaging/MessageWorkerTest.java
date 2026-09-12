package com.lrj.wms.runtime.messaging;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实后台执行验证：健康轮次进行中不抖动，失败与停机不能沿用成功状态。 */
class MessageWorkerTest {
    @Test void reportsLastCompletedPulseWhileNextPulseIsRunning() throws Exception {
        var secondStarted = new CountDownLatch(1);
        var finishSecond = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var worker = new MessageWorker("health-test", () -> {
            if (calls.incrementAndGet() == 1) return;
            secondStarted.countDown();
            try { finishSecond.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("isolated failure");
        });
        assertFalse(worker.lastPulseSucceeded());
        worker.start();
        try {
            assertTrue(secondStarted.await(3,TimeUnit.SECONDS));
            assertTrue(worker.lastPulseSucceeded());
            finishSecond.countDown();
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime()<deadline && worker.lastPulseSucceeded()) Thread.sleep(10);
            assertFalse(worker.lastPulseSucceeded());
        } finally { finishSecond.countDown();worker.stop(); }
        assertFalse(worker.lastPulseSucceeded());
    }
}
