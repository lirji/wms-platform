package com.lrj.wms.inventory.compat;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.recon.WarehouseQuantityFact;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 观察开关不改变 N/N-1 接受结果；未知 schema 必须拒绝。 */
class CompatibilityGateTest {
    @AfterEach
    void reset() {
        System.clearProperty(CompatibilityGate.OBSERVE_PROPERTY);
        CompatibilityGate.resetObservation();
    }

    @Test
    void observeFlagDoesNotChangeAcceptOrReject() {
        CompatibilityGate.resetObservation();
        System.setProperty(CompatibilityGate.OBSERVE_PROPERTY, "false");
        assertEquals(CompatibilityGate.Decision.ACCEPT_N_MINUS_1,
                CompatibilityGate.decideEvent("{\"onHandAfter\":\"1\"}"));
        assertEquals(CompatibilityGate.Decision.ACCEPT_CURRENT,
                CompatibilityGate.decideEvent("{\"schemaVersion\":1,\"onHandAfter\":\"1\"}"));
        assertEquals(CompatibilityGate.Decision.REJECT_UNKNOWN,
                CompatibilityGate.decideEvent("{\"schemaVersion\":2,\"onHandAfter\":\"1\"}"));
        assertEquals(0, CompatibilityGate.acceptedNMinusOne());
        assertEquals(0, CompatibilityGate.acceptedCurrent());
        assertEquals(0, CompatibilityGate.rejectedUnknown());

        System.setProperty(CompatibilityGate.OBSERVE_PROPERTY, "true");
        assertEquals(CompatibilityGate.Decision.ACCEPT_N_MINUS_1,
                CompatibilityGate.decideEvent("{\"onHandAfter\":\"1\"}"));
        assertEquals(CompatibilityGate.Decision.ACCEPT_CURRENT,
                CompatibilityGate.decideEvent("{\"schemaVersion\":1,\"onHandAfter\":\"1\"}"));
        assertEquals(CompatibilityGate.Decision.REJECT_UNKNOWN,
                CompatibilityGate.decideEvent("{\"schemaVersion\":2,\"onHandAfter\":\"1\"}"));
        assertEquals(1, CompatibilityGate.acceptedNMinusOne());
        assertEquals(1, CompatibilityGate.acceptedCurrent());
        assertEquals(1, CompatibilityGate.rejectedUnknown());
    }

    @Test
    void snapshotRejectsUnknownSchemaAndMoneyFields() {
        Map<String, Object> fact = WarehouseQuantityFact.onHand("F-1", "ENT-1", "WH-A", "OWN", "SKU", "LOT", null,
                new BigDecimal("2"), "EA", "C-1", "WM-1");
        fact.put("traceNote", "optional-extra");
        CompatibilityGate.requireQuantityFact(fact);
        fact.put("schemaVersion", 9);
        JobRunException unknown = assertThrows(JobRunException.class, () -> CompatibilityGate.requireQuantityFact(fact));
        assertEquals("SCHEMA_UNSUPPORTED", unknown.code());
        fact.put("schemaVersion", 1);
        fact.put("currency", "USD");
        JobRunException money = assertThrows(JobRunException.class, () -> CompatibilityGate.requireQuantityFact(fact));
        assertEquals("QUANTITY_NOT_MONEY", money.code());
    }

    @Test
    void malformedAndSpacedUnknownVersionsCannotBypassGate() {
        for (String payload : new String[] {"null", "[]", "{", "{} {}", "{\"schemaVersion\" : 9}",
                "{\"schemaVersion\":1.5}", "{\"schemaVersion\":\"1\"}", "{\"schemaVersion\":null}",
                "{\"schemaVersion\":1,\"schemaVersion\":9}"}) {
            assertEquals(CompatibilityGate.Decision.REJECT_UNKNOWN, CompatibilityGate.decideEvent(payload), payload);
        }
        assertEquals(CompatibilityGate.Decision.ACCEPT_CURRENT,
                CompatibilityGate.decideEvent("{ \"schemaVersion\" : 1 }"));
        assertEquals(new BigDecimal("3.5"), CompatibilityGate.decimalField("{\"onHandAfter\" : \"3.5\"}", "onHandAfter"));
        assertEquals(new BigDecimal("3.5"), CompatibilityGate.decimalField("{\"onHandAfter\" : 3.5}", "onHandAfter"));
        for (String payload : new String[] {"{}", "{\"onHandAfter\":null}", "{\"onHandAfter\":true}",
                "{\"onHandAfter\":\"bad\"}"}) {
            assertThrows(JobRunException.class, () -> CompatibilityGate.decimalField(payload, "onHandAfter"));
        }
    }

    @Test
    void decorateDoesNotRewriteExistingSchema() {
        String decorated = CompatibilityGate.decorateEvent("{\"onHandAfter\":\"3\"}");
        assertTrue(decorated.startsWith("{\"schemaVersion\":1,"));
        assertEquals(decorated, CompatibilityGate.decorateEvent(decorated));
    }
}
