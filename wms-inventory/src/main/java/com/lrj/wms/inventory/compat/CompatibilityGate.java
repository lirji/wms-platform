package com.lrj.wms.inventory.compat;

import com.lrj.wms.inventory.jobs.JobRunException;
import com.lrj.wms.inventory.recon.WarehouseQuantityFact;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * N/N-1 事件与快照门禁。schemaVersion 与 aggregateVersion 分开；
 * 观察开关只计数，不改变接受或拒绝。
 */
public final class CompatibilityGate {
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
        if (payload == null || payload.indexOf("\"schemaVersion\"") < 0) {
            return record(Decision.ACCEPT_N_MINUS_1);
        }
        return decide(readSchemaVersion(payload, CURRENT_EVENT_SCHEMA), CURRENT_EVENT_SCHEMA);
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
        if (fact == null || !fact.containsKey("schemaVersion")) {
            return record(Decision.ACCEPT_N_MINUS_1);
        }
        return decideSnapshot(asInt(fact.get("schemaVersion")));
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
        Object unit = fact.get("unit");
        if (unit == null || String.valueOf(unit).isBlank()) {
            throw new JobRunException("INVALID_UNIT", "数量事实必须带单位");
        }
    }

    /** 给当前生产者补 schemaVersion；旧消费者只读业务字段即可忽略它。 */
    public static String decorateEvent(String payload) {
        if (payload == null || payload.isBlank() || payload.indexOf("\"schemaVersion\"") >= 0) {
            return payload;
        }
        String trimmed = payload.strip();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return payload;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).strip();
        if (inner.isEmpty()) {
            return "{\"schemaVersion\":" + CURRENT_EVENT_SCHEMA + "}";
        }
        return "{\"schemaVersion\":" + CURRENT_EVENT_SCHEMA + "," + inner + "}";
    }

    public static int readSchemaVersion(String json, int defaultVersion) {
        if (json == null) {
            return defaultVersion;
        }
        String key = "\"schemaVersion\":";
        int start = json.indexOf(key);
        if (start < 0) {
            return defaultVersion;
        }
        start += key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) {
            return defaultVersion;
        }
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end < 0 ? defaultVersion : Integer.parseInt(json.substring(start + 1, end));
        }
        int end = start;
        if (json.charAt(end) == '-') {
            end++;
        }
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return start == end ? defaultVersion : Integer.parseInt(json.substring(start, end));
    }

    private static Decision decide(int version, int current) {
        Decision decision;
        if (version == current) {
            decision = Decision.ACCEPT_CURRENT;
        } else if (version > 0 && version < current) {
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

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
