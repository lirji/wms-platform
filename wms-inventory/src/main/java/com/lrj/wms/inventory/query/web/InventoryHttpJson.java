package com.lrj.wms.inventory.query.web;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP 数量用十进制字符串，避免 Jackson 把 DECIMAL 写成 JSON number。 */
public final class InventoryHttpJson {
    private InventoryHttpJson() {}

    /** 将 body 的协议参数传递给现有处理入口，保持统一的 HTTP 边界与错误转换。 */
    public static Map<String, Object> body(Map<String, Object> source) {
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), jsonValue(entry.getValue()));
        }
        return item;
    }

    /** 将 rows 的协议参数传递给现有处理入口，保持统一的 HTTP 边界与错误转换。 */
    public static List<Map<String, Object>> rows(List<Map<String, Object>> source) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : source) {
            items.add(body(row));
        }
        return items;
    }

    static Object jsonValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
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
                items.add(item instanceof Map<?, ?> map ? body(cast(map)) : jsonValue(item));
            }
            return items;
        }
        if (value instanceof Map<?, ?> map) {
            return body(cast(map));
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
