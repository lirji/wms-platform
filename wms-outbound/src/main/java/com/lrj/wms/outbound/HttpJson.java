package com.lrj.wms.outbound;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP 数量用十进制字符串，时间用 Instant。 */
public final class HttpJson {
    private HttpJson() {
    }

    public static Map<String, Object> page(List<Map<String, Object>> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            rows.add(row(item));
        }
        return Map.of("items", rows, "limit", rows.size());
    }

    public static Map<String, Object> cursorPage(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) source.get("items");
        Map<String, Object> body = new LinkedHashMap<>(page(items == null ? List.of() : items));
        if (source.get("limit") != null) {
            body.put("limit", source.get("limit"));
        }
        if (source.get("nextCursor") != null) {
            body.put("nextCursor", source.get("nextCursor"));
        }
        return body;
    }

    public static Map<String, Object> row(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return item;
    }

    public static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("retryable", "RECOVERY_PENDING".equals(code) || "VERSION_CONFLICT".equals(code));
        return body;
    }

    static Object jsonValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object item : list) {
                items.add(item instanceof Map<?, ?> map ? row(cast(map)) : jsonValue(item));
            }
            return items;
        }
        if (value instanceof Map<?, ?> map) {
            return row(cast(map));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
