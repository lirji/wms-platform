package com.lrj.wms.inventory.masterdata;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 种子入口必须拒绝共享基础设施，且不得静默落到 43306。 */
class SeedLocalIsolationTest {
    @Test
    void rejectsSharedDevInfraPort() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedLocal.requireIsolated("jdbc:mysql://127.0.0.1:43306/wms_inventory"));
        assertTrue(error.getMessage().contains("dev-infra"));
    }

    @Test
    void rejectsSharedHostName() {
        assertThrows(IllegalArgumentException.class,
                () -> SeedLocal.requireIsolated("jdbc:mysql://dev-infra:3306/wms_inventory"));
        assertThrows(IllegalArgumentException.class,
                () -> SeedLocal.requireIsolated("jdbc:mysql://dev_infra:3306/wms_inventory"));
    }

    @Test
    void rejectsUnnamedDatabase() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SeedLocal.requireIsolated("jdbc:mysql://127.0.0.1:18307/mysql"));
        assertTrue(error.getMessage().contains("wms_inventory"));
    }

    @Test
    void acceptsExplicitIsolatedInventoryUrl() {
        assertDoesNotThrow(() -> SeedLocal.requireIsolated(
                "jdbc:mysql://127.0.0.1:18307/wms_inventory?useSSL=false"));
    }
}
