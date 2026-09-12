package com.lrj.wms.inventory.compat;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.recon.WarehouseQuantityFact;
import java.util.Map;
import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * N/N-1 事件与快照门禁。schemaVersion 与 aggregateVersion 分开；
 * 观察开关只计数，不改变接受或拒绝。
 */
public final class CompatibilityGate {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static final int CURRENT_EVENT_SCHEMA = 1;
    public static final String OBSERVE_PROPERTY = "wms.compat.observe";

    public enum Decision {
        ACCEPT_CURRENT,
        ACCEPT_N_MINUS_1,
        REJECT_UNKNOWN
    }

    private static final AtomicInteger ACCEPTED_CURRENT = new AtomicInteger();
    private static final AtomicInteger ACCEPTED_N1 = new AtomicInteger();
    private static final AtomicInteger REJECTED = new AtomicInteger();

    private CompatibilityGate() {
    }

    public static void resetObservation() {
        ACCEPTED_CURRENT.set(0);
        ACCEPTED_N1.set(0);
        REJECTED.set(0);
    }

    public static int acceptedCurrent() {
        return ACCEPTED_CURRENT.get();
    }

    public static int acceptedNMinusOne() {
        return ACCEPTED_N1.get();
    }

    public static int rejectedUnknown() {
        return REJECTED.get();
    }

    public static Decision decideEvent(String payload) {
        try {
            JsonNode object = object(payload);
            if (!object.has("schemaVersion")) return record(Decision.ACCEPT_N_MINUS_1);
            return decide(version(object.get("schemaVersion")), CURRENT_EVENT_SCHEMA);
        } catch (RuntimeException invalid) {
            return record(Decision.REJECT_UNKNOWN);
        }
    }

    public static Decision decideSnapshot(int schemaVersion) {
        return decide(schemaVersion, WarehouseQuantityFact.SCHEMA_VERSION);
    }

    public static void requireEvent(String payload) {
        Decision decision = decideEvent(payload);
        if (decision == Decision.REJECT_UNKNOWN) {
            throw new JobRunException("SCHEMA_UNSUPPORTED", "未知事件 schemaVersion，暂停该聚合");
        }
    }

    public static Decision decideSnapshotFact(Map<String, Object> fact) {
        if (fact == null) return record(Decision.REJECT_UNKNOWN);
        if (!fact.containsKey("schemaVersion")) {
            return record(Decision.ACCEPT_N_MINUS_1);
        }
        try { return decideSnapshot(version(JSON.valueToTree(fact.get("schemaVersion")))); }
        catch (RuntimeException invalid) { return record(Decision.REJECT_UNKNOWN); }
    }

    public static void requireQuantityFact(Map<String, Object> fact) {
        if (fact == null) {
            throw new JobRunException("SCHEMA_UNSUPPORTED", "数量事实不能为空");
        }
        Decision decision = decideSnapshotFact(fact);
        if (decision == Decision.REJECT_UNKNOWN) {
            throw new JobRunException("SCHEMA_UNSUPPORTED", "未知快照 schemaVersion：" + fact.get("schemaVersion"));
        }
        if (fact.containsKey("currency") || fact.containsKey("amountMinor")) {
            throw new JobRunException("QUANTITY_NOT_MONEY", "数量事实禁止 currency/amountMinor");
        }
        Object quantity = fact.get("quantity");
        if (!(quantity instanceof String) || ((String) quantity).isBlank()) {
            throw new JobRunException("INVALID_QUANTITY", "数量必须是十进制字符串");
        }
        try { new BigDecimal((String) quantity); }
        catch (NumberFormatException invalid) { throw new JobRunException("INVALID_QUANTITY", "数量必须是十进制字符串"); }
        Object unit = fact.get("unit");
        if (unit == null || String.valueOf(unit).isBlank()) {
            throw new JobRunException("INVALID_UNIT", "数量事实必须带单位");
        }
    }

    /** 给当前生产者补 schemaVersion；旧消费者只读业务字段即可忽略它。 */
    public static String decorateEvent(String payload) {
        var object = object(payload);
        if (object.has("schemaVersion")) {
            requireEvent(payload);
            return payload;
        }
        var decorated = JSON.createObjectNode().put("schemaVersion", CURRENT_EVENT_SCHEMA);
        object.properties().forEach(entry -> decorated.set(entry.getKey(), entry.getValue()));
        return JSON.writeValueAsString(decorated);
    }

    /** 仅合法对象缺少版本才使用旧版默认值，畸形输入不得降级为兼容版本。 */
    public static int readSchemaVersion(String json, int defaultVersion) {
        JsonNode object = object(json);
        return object.has("schemaVersion") ? version(object.get("schemaVersion")) : defaultVersion;
    }

    /** 消息允许十进制字符串或 JSON 数字；缺失、布尔和错误格式不能变成零库存。 */
    public static BigDecimal decimalField(String payload, String name) {
        JsonNode node = object(payload).get(name);
        if (node == null || !(node.isString() || node.isNumber())) {
            throw new JobRunException("INVALID_EVENT_PAYLOAD", "库存事件缺少有效数量字段：" + name);
        }
        try {
            BigDecimal value = node.isNumber() ? node.decimalValue() : new BigDecimal(node.asString());
            if (value.scale() > 6 || value.precision() - value.scale() > 14) throw new NumberFormatException();
            return value;
        } catch (RuntimeException invalid) {
            throw new JobRunException("INVALID_EVENT_PAYLOAD", "库存事件数量格式无效：" + name);
        }
    }

    private static JsonNode object(String payload) {
        try {
            JsonNode node = JSON.readTree(payload);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return node;
        } catch (RuntimeException invalid) {
            throw new JobRunException("INVALID_EVENT_PAYLOAD", "消息必须是无重复字段的完整 JSON 对象");
        }
    }

    private static int version(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new JobRunException("SCHEMA_UNSUPPORTED", "版本必须是整数");
        }
        return node.intValue();
    }

    private static Decision decide(int version, int current) {
        Decision decision;
        if (version == current) {
            decision = Decision.ACCEPT_CURRENT;
        } else if (version > 0 && version == current - 1) {
            decision = Decision.ACCEPT_N_MINUS_1;
        } else {
            decision = Decision.REJECT_UNKNOWN;
        }
        return record(decision);
    }

    private static Decision record(Decision decision) {
        if (Boolean.parseBoolean(System.getProperty(OBSERVE_PROPERTY, "false"))) {
            switch (decision) {
                case ACCEPT_CURRENT -> ACCEPTED_CURRENT.incrementAndGet();
                case ACCEPT_N_MINUS_1 -> ACCEPTED_N1.incrementAndGet();
                case REJECT_UNKNOWN -> REJECTED.incrementAndGet();
            }
        }
        return decision;
    }

}
