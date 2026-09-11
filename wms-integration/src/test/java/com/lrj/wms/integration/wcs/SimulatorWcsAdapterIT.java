package com.lrj.wms.integration.wcs;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** S5-02：WCS 端口由明确标识的 simulator 实现。不是真实设备，不写库存。 */
class SimulatorWcsAdapterIT {
    private static final Instant NOW = Instant.parse("2026-09-12T07:20:00Z");

    @Test
    void dispatchQueryReceiptStayOnSameCommandIdentity() {
        SimulatorWcsAdapter adapter = new SimulatorWcsAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(SimulatorWcsAdapter.IMPLEMENTATION, adapter.implementation());
        assertEquals("SIMULATOR", adapter.implementation());
        WcsCommand command = new WcsCommand("ENT-1", "WH-A", "DEV-CMD-1", "PICK", "TASK-1", new BigDecimal("3"),
                "digest-1");
        WcsDispatchResult first = adapter.dispatch(command);
        assertFalse(first.replayed());
        assertEquals("DISPATCHED", first.state());
        WcsDispatchResult replay = adapter.dispatch(command);
        assertTrue(replay.replayed());
        assertEquals("DISPATCHED", replay.state());
        WcsAdapterException conflict = assertThrows(WcsAdapterException.class,
                () -> adapter.dispatch(new WcsCommand("ENT-1", "WH-A", "DEV-CMD-1", "PICK", "TASK-1",
                        new BigDecimal("4"), "digest-2")));
        assertEquals("COMMAND_CONFLICT", conflict.code());
        assertTrue(adapter.query("ENT-1", "WH-A", "MISSING").isEmpty());
        WcsCommandView view = adapter.query("ENT-1", "WH-A", "DEV-CMD-1").orElseThrow();
        assertEquals("DISPATCHED", view.state());
        assertEquals("TASK-1", view.sourceTaskId());
        WcsReceiptResult accepted = adapter.accept(new WcsReceipt("ENT-1", "WH-A", "DEV-CMD-1", "EVT-1", "COMPLETED",
                new BigDecimal("3"), NOW));
        assertFalse(accepted.replayed());
        assertEquals("COMPLETED", accepted.commandState());
        WcsReceiptResult receiptReplay = adapter.accept(new WcsReceipt("ENT-1", "WH-A", "DEV-CMD-1", "EVT-1",
                "COMPLETED", new BigDecimal("3"), NOW));
        assertTrue(receiptReplay.replayed());
        WcsAdapterException unknown = assertThrows(WcsAdapterException.class,
                () -> adapter.accept(new WcsReceipt("ENT-1", "WH-A", "DEV-MISSING", "EVT-2", "COMPLETED",
                        new BigDecimal("1"), NOW)));
        assertEquals("UNKNOWN_COMMAND", unknown.code());
        assertEquals("COMPLETED", adapter.query("ENT-1", "WH-A", "DEV-CMD-1").map(WcsCommandView::state).orElse(""));
        WcsCommand unknownCmd = new WcsCommand("ENT-1", "WH-A", "DEV-CMD-2", "PICK", "TASK-2", new BigDecimal("1"),
                "digest-u");
        adapter.dispatch(unknownCmd);
        adapter.accept(new WcsReceipt("ENT-1", "WH-A", "DEV-CMD-2", "EVT-U", "UNKNOWN", BigDecimal.ZERO, NOW));
        assertEquals("UNKNOWN", adapter.query("ENT-1", "WH-A", "DEV-CMD-2").map(WcsCommandView::state).orElse(""));
        assertEquals(Optional.empty(), adapter.query("ENT-1", "WH-A", "MISSING"));
        System.out.println("S5_WCS_SIMULATOR: labeled SIMULATOR; same deviceCommandId; UNKNOWN kept; no inventory write");
    }
}
