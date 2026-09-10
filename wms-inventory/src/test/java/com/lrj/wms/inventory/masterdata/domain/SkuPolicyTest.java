package com.lrj.wms.inventory.masterdata.domain;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** AC-02 领域侧：精度、开关和换算失败必须拒绝，且不编造效期日界。 */
class SkuPolicyTest {
    @Test
    void serialSkuRejectsFractionalScale() {
        var error = assertThrows(IllegalArgumentException.class, () -> sample(1, false, true, false));
        assertTrue(error.getMessage().contains("整数"));
    }

    @Test
    void unknownStateDoesNotFallbackToActive() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> SkuPolicy.create("S1", "E1", "SKU", "n", "EA", 0, false, false, false, 1, "OPEN"));
        assertTrue(error.getMessage().contains("未知资源状态"));
    }

    @Test
    void unknownGateStateDoesNotFallbackToOpen() {
        var error = assertThrows(IllegalArgumentException.class, () -> MasterdataCodes.requireGateState("ACTIVE"));
        assertTrue(error.getMessage().contains("未知门禁状态"));
    }

    @Test
    void conversionRejectsTruncation() {
        SkuPolicy sku = sample(0, false, false, false);
        var error = assertThrows(IllegalArgumentException.class,
                () -> sku.toBaseQuantity(new BigDecimal("1"), BigDecimal.ONE, new BigDecimal("3")));
        assertTrue(error.getMessage().contains("精确换算"));
    }

    @Test
    void conversionKeepsExactIntegerResult() {
        SkuPolicy sku = sample(0, false, false, false);
        assertEquals(new BigDecimal("12"), sku.toBaseQuantity(new BigDecimal("2"), new BigDecimal("6"), BigDecimal.ONE));
    }

    @Test
    void casePackTwelveToOneConvertsExactly() {
        SkuPolicy sku = sample(0, true, false, false);
        assertEquals(new BigDecimal("12"),
                sku.toBaseQuantity(BigDecimal.ONE, new BigDecimal("12"), BigDecimal.ONE));
    }

    @Test
    void nonLotSkuOnlyAllowsSentinel() {
        SkuPolicy sku = sample(0, false, false, false);
        sku.requireLotUsage(MasterdataCodes.NO_LOT);
        assertThrows(IllegalArgumentException.class, () -> sku.requireLotUsage("LOT-1"));
    }

    @Test
    void lotSkuRejectsSentinel() {
        SkuPolicy sku = sample(0, true, false, false);
        sku.requireLotUsage("LOT-1");
        assertThrows(IllegalArgumentException.class, () -> sku.requireLotUsage(MasterdataCodes.NO_LOT));
    }

    @Test
    void expiryDisabledRejectsTimestamps() {
        SkuPolicy sku = sample(0, true, false, false);
        assertThrows(IllegalArgumentException.class,
                () -> sku.requireExpiryFields(Instant.parse("2026-01-01T00:00:00Z"), null, null, 0));
    }

    @Test
    void expiryEnabledKeepsSourceDateWithoutInventingUtcBoundary() {
        SkuPolicy sku = sample(0, true, false, true);
        sku.requireExpiryFields(null, null, "2026-09-10", 0);
        assertThrows(IllegalArgumentException.class,
                () -> sku.requireExpiryFields(Instant.parse("2026-01-02T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                        null, 0));
    }

    @Test
    void timezoneMustBeIana() {
        assertEquals("Asia/Shanghai", SkuPolicy.requireIanaTimezone("Asia/Shanghai").getId());
        assertThrows(IllegalArgumentException.class, () -> SkuPolicy.requireIanaTimezone("Shanghai"));
    }

    private static SkuPolicy sample(int scale, boolean lot, boolean serial, boolean expiry) {
        return SkuPolicy.create("S1", "E1", "SKU-1", "demo", "EA", scale, lot, serial, expiry, 1,
                MasterdataCodes.STATE_ACTIVE);
    }
}
