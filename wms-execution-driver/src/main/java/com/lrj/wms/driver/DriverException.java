package com.lrj.wms.driver;

import java.util.Map;

/** Driver 拒绝或无法启动时抛出；已启动但失败走 ExecutionResult.status。 */
public class DriverException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public DriverException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
