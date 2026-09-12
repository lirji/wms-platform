package com.lrj.wms.runtime;

import com.lrj.wms.runtime.web.*;
import com.lrj.wms.runtime.db.DatabaseBudget;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 回归用户输入边界、跨查询游标隔离、租户公平和许可证释放。 */
class RuntimeBudgetTest {
    @Test void cursorRejectsWrongScopeAndInvalidSize() {
        var page = CursorPage.chronological(1, null, "tenant-a:warehouse-a");
        var time = java.sql.Timestamp.from(java.time.Instant.parse("2026-09-12T00:00:00Z"));
        var body = page.result(List.of(Map.of("id", "B", "created_at", time), Map.of("id", "A", "created_at", time)), true);
        String token = (String) body.get("nextCursor");
        assertEquals("B", CursorPage.chronological(1, token, page.scope()).id());
        assertThrows(InvalidPageException.class, () -> CursorPage.parse(1, token, "tenant-b:warehouse-a"));
        assertThrows(InvalidPageException.class, () -> CursorPage.parse(201, null, "a"));
        assertThrows(InvalidPageException.class, () -> CursorPage.parse(0, null, "a"));
        assertThrows(InvalidPageException.class, () -> CursorPage.parse(10, "invalid!", "a"));
        assertFalse(page.result(List.of(Map.of("id", "B", "created_at", time)), true).containsKey("nextCursor"));
    }
    @Test void hotTenantCannotUseAnotherTenantsConcurrency() {
        var gate = new AdmissionGate(new AdmissionBudget(2, 1, 100, 100));
        var a = gate.acquire("A");
        assertNotNull(a);
        assertNull(gate.acquire("A"));
        var b = gate.acquire("B");
        assertNotNull(b);
        assertNull(gate.acquire("C"));
        a.close(); a.close();
        try (var c = gate.acquire("C")) { assertNotNull(c); assertNull(gate.acquire("D")); }
        b.close();
    }
    @Test void rateLimitSurvivesConcurrencyPermitRelease() {
        var gate = new AdmissionGate(new AdmissionBudget(2, 1, 100, 1));
        gate.acquire("A").close();
        assertNull(gate.acquire("A"));
        assertNotNull(gate.acquire("B"));
    }
    @Test void invalidConfigurationFailsBeforeOpeningConnections() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseBudget(0, 0, 1000, 500, 5, 1000, 10000));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionBudget(1, 2, 10, 1));
    }
}
