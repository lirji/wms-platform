package com.lrj.wms.driver.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DriverAuditLoggerTest {
    @Test
    void secretKeysAreRedacted() {
        assertEquals("***", DriverAuditLogger.redact("apiKey", "cursor_secret"));
        assertEquals("***", DriverAuditLogger.redact("TOKEN", "abc"));
        assertEquals("visible", DriverAuditLogger.redact("status", "visible"));
    }
}
