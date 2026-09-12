package com.lrj.wms.fulfillment.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 履约种子必须拒绝共享基础设施。 */
class SeedFulfillmentIsolationTest {
    @Test
    void rejectsSharedDevInfraPort() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedFulfillment.requireIsolated("jdbc:mysql://127.0.0.1:43306/wms_fulfillment"));
        assertTrue(error.getMessage().contains("dev-infra"));
    }

    @Test
    void rejectsWrongDatabase() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedFulfillment.requireIsolated("jdbc:mysql://127.0.0.1:18306/wms_inbound"));
        assertTrue(error.getMessage().contains("wms_fulfillment"));
    }

    @Test
    void acceptsIsolatedFulfillmentUrl() {
        assertDoesNotThrow(() -> SeedFulfillment.requireIsolated("jdbc:mysql://127.0.0.1:18306/wms_fulfillment"));
    }
}
