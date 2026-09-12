package com.lrj.wms.inventory.masterdata;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 主数据 HTTP 信封与错误体。 */
final class MasterdataHttp {
    private MasterdataHttp() {
    }

    static Map<String, Object> page(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", jsonRows(items));
        body.put("limit", items.size());
        return body;
    }

    static Map<String, Object> row(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return item;
    }

    static List<Map<String, Object>> jsonRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(row(row));
        }
        return items;
    }

    static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", com.lrj.wms.runtime.observability.RequestCorrelationFilter.currentId());
        body.put("retryable", "VERSION_CONFLICT".equals(code) || "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(code));
        return body;
    }

    static ResponseEntity<Map<String, Object>> statusOf(MasterdataException error) {
        HttpStatus status = switch (error.code()) {
            case "WAREHOUSE_NOT_FOUND", "LOCATION_NOT_FOUND", "GATE_NOT_FOUND", "SKU_NOT_FOUND", "LOT_NOT_FOUND",
                    "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE_DOCUMENT", "IDEMPOTENCY_PAYLOAD_MISMATCH", "VERSION_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(error(error.code(), error.getMessage()));
    }

    static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    static String requireText(Map<String, Object> body, String key, String label) {
        String value = text(body, key);
        if (value == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }

    static boolean bool(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            throw new IllegalArgumentException(key + "不能为空");
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    static int integer(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + "不能为空");
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static long longValue(Map<String, Object> body, String key, long fallback) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    static BigDecimal decimal(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    static String requireMatchingKey(String header, String body) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key不能为空");
        }
        if (body != null && !body.isBlank() && !header.equals(body)) {
            throw new IllegalArgumentException("clientOperationId必须与Idempotency-Key一致");
        }
        return header;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return com.lrj.wms.runtime.db.DatabaseInstants.require(localDateTime).toString();
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
