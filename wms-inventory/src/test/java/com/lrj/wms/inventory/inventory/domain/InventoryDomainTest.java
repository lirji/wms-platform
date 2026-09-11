package com.lrj.wms.inventory.inventory.domain;

import com.lrj.wms.inventory.masterdata.domain.MasterdataCodes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** S2-01：数量精度、桶键、预占状态与门禁策略均须封闭集合，未知码不得成功。 */
class InventoryDomainTest {
    @Test
    void quantityRejectsTruncationAndUnknownScale() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(new BigDecimal("1.23"), 1));
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(BigDecimal.ONE, 7));
        assertThrows(IllegalArgumentException.class, () -> Quantity.parse("1e2", 0));
        Quantity twelve = Quantity.parse("12", 0);
        assertEquals("12", twelve.toPlainString());
        assertEquals(Quantity.parse("12.00", 2), Quantity.parse("12", 2));
    }

    @Test
    void quantityArithmeticRequiresSameScale() {
        Quantity a = Quantity.parse("1.50", 2);
        Quantity b = Quantity.parse("0.50", 2);
        assertEquals("2.00", a.plus(b).toPlainString());
        assertThrows(IllegalArgumentException.class, () -> a.plus(Quantity.parse("1", 0)));
        assertTrue(Quantity.parse("-1", 0).isNegative());
        assertThrows(IllegalArgumentException.class, () -> Quantity.parse("100000000000000", 0));
    }

    @Test
    void bucketKeyRejectsNullLotAndUnknownQuality() {
        assertThrows(IllegalArgumentException.class,
                () -> StockBucketKey.of("E", "W", "O", "L", "S", null, InventoryCodes.QUALITY_GOOD));
        assertThrows(IllegalArgumentException.class,
                () -> StockBucketKey.of("E", "W", "O", "L", "S", MasterdataCodes.NO_LOT, "AVAILABLE"));
        StockBucketKey hold = StockBucketKey.of("E", "W", "O", "LOC-B", "S", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_HOLD);
        StockBucketKey good = StockBucketKey.of("E", "W", "O", "LOC-A", "S", MasterdataCodes.NO_LOT,
                InventoryCodes.QUALITY_GOOD);
        assertEquals(List.of(good, hold), StockBucketKey.lockOrder(List.of(hold, good, hold)));
    }

    @Test
    void reservationUnknownCodeDoesNotFallbackToTried() {
        assertThrows(IllegalArgumentException.class, () -> ReservationState.require("HELD"));
        assertThrows(IllegalArgumentException.class, () -> ReservationState.require("EXPIRED"));
        assertTrue(ReservationState.occupiesReserved(ReservationState.TRIED));
        assertTrue(ReservationState.occupiesReserved(ReservationState.CONFIRMED));
        assertFalse(ReservationState.occupiesReserved(ReservationState.CANCELLED));
        assertEquals(ReservationState.CONFIRMED, ReservationState.requireTransition(ReservationState.TRIED,
                ReservationState.CONFIRMED, ReservationState.CAUSE_TCC_CONFIRM));
        assertThrows(IllegalArgumentException.class, () -> ReservationState.requireTransition(ReservationState.CONFIRMED,
                ReservationState.CANCELLED, ReservationState.CAUSE_TCC_CANCEL));
        assertThrows(IllegalArgumentException.class, () -> ReservationState.requireTransition(ReservationState.TRIED,
                ReservationState.RELEASED, ReservationState.CAUSE_BUSINESS_RELEASE_COMPLETE));
    }

    @Test
    void allocationPolicyHasNoSilentDefault() {
        assertThrows(IllegalArgumentException.class, () -> InventoryCodes.requireAllocationPolicy(null));
        assertThrows(IllegalArgumentException.class, () -> InventoryCodes.requireAllocationPolicy("NONE"));
        assertEquals(InventoryCodes.ALLOC_FEFO, InventoryCodes.requireAllocationPolicy("FEFO"));
    }

    @Test
    void balanceInvariantAndAvailableQualification() {
        Quantity ten = Quantity.parse("10", 0);
        Quantity four = Quantity.parse("4", 0);
        Quantity two = Quantity.parse("2", 0);
        InventoryPolicy.requireBalanceInvariant(ten, four, two);
        assertThrows(IllegalArgumentException.class,
                () -> InventoryPolicy.requireBalanceInvariant(ten, Quantity.parse("9", 0), two));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryPolicy.requireNonNegative("on_hand", Quantity.parse("-1", 0)));
        assertEquals("4", InventoryPolicy.nonSerialAvailable(ten, four, two, true, InventoryCodes.QUALITY_GOOD, true,
                false).toPlainString());
        assertEquals("0", InventoryPolicy.nonSerialAvailable(ten, four, two, true, InventoryCodes.QUALITY_HOLD, true,
                false).toPlainString());
        assertEquals("0", InventoryPolicy.nonSerialAvailable(ten, four, two, true, InventoryCodes.QUALITY_GOOD, true,
                true).toPlainString());
        assertEquals("0", InventoryPolicy.nonSerialAvailable(ten, four, two, true, InventoryCodes.QUALITY_GOOD, false,
                false).toPlainString());
        assertThrows(IllegalArgumentException.class, InventoryPolicy::rejectBucketFormulaForSerial);
    }

    @Test
    void expiryUsesHalfOpenInterval() {
        Instant now = Instant.parse("2026-09-11T10:00:00Z");
        assertTrue(ExpiryPolicy.satisfied(null, now));
        assertTrue(ExpiryPolicy.satisfied(Instant.parse("2026-09-11T10:00:01Z"), now));
        assertFalse(ExpiryPolicy.satisfied(now, now));
        assertFalse(ExpiryPolicy.satisfied(Instant.parse("2026-09-11T09:59:59Z"), now));
    }

    @Test
    void gateMatrixMatchesDomain() {
        assertEquals(InventoryCodes.DECISION_ALLOW,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_OPEN, InventoryCodes.CMD_NEW_RESERVE));
        assertEquals(InventoryCodes.DECISION_DENY,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_OPEN, InventoryCodes.CMD_ARBITRARY_RELEASE));
        assertEquals(InventoryCodes.DECISION_DENY,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_QUIESCING, InventoryCodes.CMD_NEW_RESERVE));
        assertEquals(InventoryCodes.DECISION_DRAIN,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_QUIESCING, InventoryCodes.CMD_INFLIGHT_CONFIRM));
        assertEquals(InventoryCodes.DECISION_ALLOW,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_QUIESCING, InventoryCodes.CMD_TCC_CANCEL));
        assertEquals(InventoryCodes.DECISION_ISOLATE,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_FROZEN, InventoryCodes.CMD_INFLIGHT_CONFIRM));
        assertEquals(InventoryCodes.DECISION_ALLOW,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_FROZEN, InventoryCodes.CMD_COUNT_ADJUST));
        assertEquals(InventoryCodes.DECISION_DENY,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_FROZEN, InventoryCodes.CMD_NEW_RESERVE));
        assertEquals(InventoryCodes.DECISION_DENY,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_MAINTENANCE, InventoryCodes.CMD_NORMAL_MUTATION));
        assertEquals(InventoryCodes.DECISION_ALLOW,
                InventoryPolicy.decideGate(MasterdataCodes.GATE_MAINTENANCE, InventoryCodes.CMD_MAINTENANCE));
        assertThrows(IllegalArgumentException.class,
                () -> InventoryPolicy.decideGate(MasterdataCodes.GATE_OPEN, "POST"));
        assertThrows(IllegalArgumentException.class, () -> InventoryPolicy.decideGate("CLOSED", InventoryCodes.CMD_NEW_RESERVE));
    }
}
