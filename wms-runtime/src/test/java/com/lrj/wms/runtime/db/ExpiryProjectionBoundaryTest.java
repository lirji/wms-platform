package com.lrj.wms.runtime.db;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

/** 在剥离领域的 JDBC 转换依赖之前固定空值、绝对时刻和拒绝墙钟的行为。 */
class ExpiryProjectionBoundaryTest {
    @Test
    void projectionConversionKeepsAbsoluteInstantsAndNullableExpiry() {
        Instant sample = Instant.parse("2026-11-01T08:00:00Z");
        assertNull(DatabaseInstants.instantOf(null));
        assertEquals(sample, DatabaseInstants.instantOf(sample));
        assertEquals(sample, DatabaseInstants.instantOf(Timestamp.from(sample)));
        assertEquals(sample, DatabaseInstants.instantOf(Date.from(sample)));
    }

    @Test
    void projectionConversionDoesNotGuessZoneOrCoerceUnknownRepresentations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseInstants.instantOf(LocalDateTime.parse("2026-11-01T01:30:00")));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseInstants.instantOf("2026-11-01T08:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseInstants.instantOf(1L));
        assertThrows(
                UnsupportedOperationException.class,
                () -> DatabaseInstants.instantOf(java.sql.Date.valueOf("2026-11-01")));
    }
}
