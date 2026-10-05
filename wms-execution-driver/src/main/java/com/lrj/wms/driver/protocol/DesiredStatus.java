package com.lrj.wms.driver.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Manifest 中的 status 只表达期望，不等于运行时是否可执行。 */
public enum DesiredStatus {
    ACTIVE("active"),
    DISABLED("disabled");

    private final String wire;

    DesiredStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static DesiredStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("manifest status不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DesiredStatus status : values()) {
            if (status.wire.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知manifest status: " + value);
    }
}
