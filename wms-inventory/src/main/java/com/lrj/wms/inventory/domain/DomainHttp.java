package com.lrj.wms.inventory.domain;

import com.lrj.wms.inventory.inventory.InventoryException;
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

/** 移库/限制/调整 HTTP 信封。 */
final class DomainHttp {
    private DomainHttp() {
    }

    static Map<String, Object> row(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return item;
    }

    static Map<String, Object> page(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items.stream().map(DomainHttp::row).toList());
        body.put("limit", items.size());
        if (!items.isEmpty()) {
            body.put("nextCursor", items.getLast().get("id"));
        }
        return body;
    }

    static Map<String, Object> accepted(String warehouseId, String operationId, String statusUrl, String physical,
            String sync) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("status", "ACCEPTED");
        body.put("statusUrl", statusUrl);
        body.put("physicalStatus", physical);
        body.put("stockSyncStatus", sync);
        body.put("safeToRetry", true);
        body.put("warehouseId", warehouseId);
        return body;
    }

    static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("requestId", UUID.randomUUID().toString());
        body.put("retryable", "VERSION_CONFLICT".equals(code) || "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(code));
        return body;
    }

    static ResponseEntity<Map<String, Object>> statusOf(InventoryException error) {
        HttpStatus status = error.code().contains("NOT_FOUND") ? HttpStatus.NOT_FOUND
                : error.code().contains("CONFLICT") || "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(error.code())
                        || "STOCK_INSUFFICIENT".equals(error.code()) || "STOCK_FROZEN".equals(error.code())
                        || "HOLD_STATE_CONFLICT".equals(error.code()) || "ADJUSTMENT_STATE_CONFLICT".equals(error.code())
                        ? HttpStatus.CONFLICT
                        : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(error(error.code(), error.getMessage()));
    }

    static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    static String requireText(Map<String, Object> body, String key, String label) {
        String value = text(body, key);
        if (value == null) {
            throw new InventoryException("INVALID_ARGUMENT", label + "不能为空");
        }
        return value;
    }

    static String requireMatchingKey(String header, String body) {
        if (header == null || header.isBlank()) {
            throw new InventoryException("INVALID_ARGUMENT", "Idempotency-Key不能为空");
        }
        if (body != null && !body.isBlank() && !header.equals(body)) {
            throw new InventoryException("INVALID_ARGUMENT", "clientOperationId必须与Idempotency-Key一致");
        }
        return header;
    }

    static BigDecimal requireQty(Object value, String label) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new InventoryException("INVALID_QUANTITY", label + "不能为空");
        }
        BigDecimal qty = new BigDecimal(String.valueOf(value));
        if (qty.signum() == 0) {
            throw new InventoryException("INVALID_QUANTITY", label + "不能为0");
        }
        if (qty.scale() > 6) {
            throw new InventoryException("INVALID_QUANTITY", label + "精度不能超过6位");
        }
        return qty;
    }

    static long requireVersion(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new InventoryException("INVALID_VERSION", "expectedVersion不能为空");
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    static String jsonArray(Object raw) {
        if (raw == null) {
            return "[]";
        }
        if (raw instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                values.add(String.valueOf(item));
            }
            return "[\"" + String.join("\",\"", values) + "\"]";
        }
        return "[]";
    }

    private static Object jsonValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value;
    }
}
