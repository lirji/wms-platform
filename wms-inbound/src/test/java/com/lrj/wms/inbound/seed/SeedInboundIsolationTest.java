package com.lrj.wms.inbound.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 入库种子必须拒绝共享基础设施。 */
class SeedInboundIsolationTest {
    @Test
    void rejectsSharedDevInfraPort() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedInbound.requireIsolated("jdbc:mysql://127.0.0.1:43306/wms_inbound"));
        assertTrue(error.getMessage().contains("dev-infra"));
    }

    @Test
    void rejectsWrongDatabase() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedInbound.requireIsolated("jdbc:mysql://127.0.0.1:18306/wms_inventory"));
        assertTrue(error.getMessage().contains("wms_inbound"));
    }

    @Test
    void acceptsIsolatedInboundUrl() {
        assertDoesNotThrow(() -> SeedInbound.requireIsolated("jdbc:mysql://127.0.0.1:18306/wms_inbound?useSSL=false"));
    }
}
