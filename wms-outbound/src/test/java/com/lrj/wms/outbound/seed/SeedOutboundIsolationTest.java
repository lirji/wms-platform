package com.lrj.wms.outbound.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出库种子必须拒绝共享基础设施。 */
class SeedOutboundIsolationTest {
    @Test
    void rejectsSharedDevInfraPort() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedOutbound.requireIsolated("jdbc:mysql://127.0.0.1:43306/wms_outbound"));
        assertTrue(error.getMessage().contains("dev-infra"));
    }

    @Test
    void rejectsWrongDatabase() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedOutbound.requireIsolated("jdbc:mysql://127.0.0.1:18306/wms_inbound"));
        assertTrue(error.getMessage().contains("wms_outbound"));
    }

    @Test
    void acceptsIsolatedOutboundUrl() {
        assertDoesNotThrow(() -> SeedOutbound.requireIsolated("jdbc:mysql://127.0.0.1:18306/wms_outbound"));
    }
}
