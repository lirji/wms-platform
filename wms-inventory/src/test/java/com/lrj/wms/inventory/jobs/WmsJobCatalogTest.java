package com.lrj.wms.inventory.jobs;

import com.lrj.wms.fulfillment.AllocationRecoverySweep;
import com.lrj.wms.inventory.tcc.TccReservationWatch;
import com.lrj.wms.outbound.jobs.DeviceUnknownResultSweep;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** S7-01：目录名与各服务 handler 对齐。 */
class WmsJobCatalogTest {
    @Test
    void catalogHasTenStableBeanHandlers() {
        assertEquals(10, WmsJobCatalog.HANDLERS.size());
        assertEquals(10, new HashSet<>(WmsJobCatalog.HANDLERS).size());
        assertEquals(WmsJobCatalog.TCC_RESERVATION_WATCH, TccReservationWatch.HANDLER);
        assertEquals(WmsJobCatalog.ALLOCATION_RECOVERY_SWEEP, AllocationRecoverySweep.HANDLER);
        assertEquals(WmsJobCatalog.DEVICE_UNKNOWN_RESULT_SWEEP, DeviceUnknownResultSweep.HANDLER);
    }
}
