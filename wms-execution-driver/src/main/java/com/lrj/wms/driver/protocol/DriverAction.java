package com.lrj.wms.driver.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

/** 任务动作必须与 manifest 白名单对齐，未知值 fail-closed。 */
public enum DriverAction {
    READ_REPO("read_repo"),
    WRITE_DESIGN_DOCS("write_design_docs"),
    MODIFY_PRODUCT_CODE("modify_product_code"),
    MODIFY_TESTS("modify_tests"),
    MODIFY_RUNTIME_FILES("modify_runtime_files"),
    EXECUTE_LOCAL_COMMANDS("execute_local_commands"),
    EXECUTE_TESTS("execute_tests");

    private final String wire;

    DriverAction(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static DriverAction fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("action不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.wire.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知action: " + value));
    }
}
