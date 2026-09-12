package com.lrj.wms.inventory.migrate;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 路由表或分片规则缺失不得伪装成停写。 */
class WarehouseRouteGateTest {
    @Test
    void missingTableOrShardingRuleIsNotStaleRoute() {
        assertTrue(WarehouseMigrationService.isAbsentRouteControl(
                new SQLException("Table 'inv.warehouse_route' doesn't exist", "42S02")));
        assertTrue(WarehouseMigrationService.isAbsentRouteControl(new IllegalStateException(
                "Can not find table rule of `warehouse_route` in schema `cell_WH-A`")));
        assertFalse(WarehouseMigrationService.isAbsentRouteControl(new SQLException("deadlock", "40001")));
    }
}
