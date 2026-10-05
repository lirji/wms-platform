package com.lrj.wms.driver.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

/** 风险等级来自 manifest，未知值必须拒绝而不是降级执行。 */
public enum RiskClass {
    NORMAL("NORMAL");

    private final String wire;

    RiskClass(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RiskClass fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("riskClass不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.wire.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知riskClass: " + value));
    }
}
