package com.lrj.wms.integration.wcs.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.wms.integration.wcs.error.WcsAdapterException;
import com.lrj.wms.integration.wcs.model.WcsCommand;
import com.lrj.wms.integration.wcs.model.WcsReceipt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 验证模拟器拒绝路径没有幂等副作用，以及并发重放只能观察已完成的同一结果。 */
class SimulatorWcsAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-12T07:20:00Z");

    private static SimulatorWcsAdapter adapter() {
        var adapter = new SimulatorWcsAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
        adapter.dispatch(
                new WcsCommand("ENT", "WH", "CMD", "PICK", "TASK", BigDecimal.ONE, "digest"));
        return adapter;
    }

    private static WcsReceipt receipt(String state) {
        return new WcsReceipt("ENT", "WH", "CMD", "EVENT", state, BigDecimal.ONE, NOW);
    }

    @Test
    void rejectedReceiptDoesNotConsumeEventIdentityOrBecomeASuccessfulReplay() {
        var adapter = adapter();
        for (int i = 0; i < 2; i++) {
            assertEquals(
                    "INVALID_RECEIPT",
                    assertThrows(
                                    WcsAdapterException.class,
                                    () -> adapter.accept(receipt("INVALID")))
                            .code());
        }
        assertEquals("DISPATCHED", adapter.query("ENT", "WH", "CMD").orElseThrow().state());
        assertFalse(adapter.accept(receipt("COMPLETED")).replayed());
        assertTrue(adapter.accept(receipt("COMPLETED")).replayed());
    }

    @Test
    void concurrentReceiptReplaysReturnOneCompletedBusinessResult() throws Exception {
        var adapter = adapter();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var results =
                    new ArrayList<
                            java.util.concurrent.Future<
                                    com.lrj.wms.integration.wcs.model.WcsReceiptResult>>();
            for (int i = 0; i < 12; i++) {
                results.add(
                        executor.submit(
                                () -> {
                                    assertTrue(start.await(5, TimeUnit.SECONDS));
                                    return adapter.accept(receipt("COMPLETED"));
                                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : results) {
                var result = future.get(5, TimeUnit.SECONDS);
                assertEquals("COMPLETED", result.commandState());
                if (!result.replayed()) accepted++;
            }
            assertEquals(1, accepted);
        } finally {
            executor.shutdownNow();
        }
    }
}
