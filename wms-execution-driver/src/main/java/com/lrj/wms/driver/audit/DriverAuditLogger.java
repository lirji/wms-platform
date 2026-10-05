package com.lrj.wms.driver.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 审计只记执行身份与结果，敏感环境变量不得入日志。 */
public final class DriverAuditLogger {
    private static final Logger log = LoggerFactory.getLogger(DriverAuditLogger.class);
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i).*(token|password|secret|api[_-]?key|authorization|credential).*");

    public void record(Map<String, Object> fields) {
        StringBuilder line = new StringBuilder("driver.audit");
        fields.forEach(
                (key, value) -> {
                    line.append(' ').append(key).append('=').append(redact(key, value));
                });
        log.info(line.toString());
    }

    static Object redact(String key, Object value) {
        if (key != null && SECRET_KEY.matcher(key).matches()) {
            return "***";
        }
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.toLowerCase(Locale.ROOT).contains("cursor_")
                || text.toLowerCase(Locale.ROOT).contains("sk-")) {
            return "***";
        }
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }
}
