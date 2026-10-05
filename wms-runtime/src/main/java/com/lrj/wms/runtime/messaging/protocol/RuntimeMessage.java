package com.lrj.wms.runtime.messaging.protocol;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** 跨服务信封显式携带范围与原始业务时刻；payload由目标服务的版本化适配器解释。 */
public record RuntimeMessage(
        int schemaVersion,
        String eventId,
        String sourceService,
        String enterpriseId,
        String warehouseId,
        String eventType,
        String aggregateId,
        long aggregateVersion,
        String occurredAt,
        String requestId,
        JsonNode payload) {
    /** 信封与所有传输适配器共享同一字节预算，协议解析不依赖 Kafka 实现。 */
    public static final int MAX_PAYLOAD_BYTES = 262144;

    public static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    /** 在不可变契约的构造边界统一处理输入，保证默认值、校验与字段复制不在各调用点分叉。 */
    public RuntimeMessage {
        if (schemaVersion != 1 || aggregateVersion < 0)
            throw new MessageRejectedException("SCHEMA_UNSUPPORTED");
        for (String id :
                new String[] {
                    eventId, sourceService, enterpriseId, warehouseId, eventType, aggregateId
                }) {
            if (id == null || id.isBlank() || id.length() > 64)
                throw new MessageRejectedException("INVALID_MESSAGE_SCOPE");
        }
        if (payload == null || !payload.isObject())
            throw new MessageRejectedException("INVALID_EVENT_PAYLOAD");
        Instant.parse(occurredAt);
        if (requestId != null && !requestId.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new MessageRejectedException("INVALID_REQUEST_ID");
    }

    /** 在协议入口解析并校验既有信封，未知或不完整字段不能进入业务处理。 */
    public static RuntimeMessage parse(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new MessageRejectedException("MESSAGE_TOO_LARGE");
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null
                    || !node.isObject()
                    || !node.path("schemaVersion").isIntegralNumber()
                    || !node.path("schemaVersion").canConvertToInt()
                    || node.path("schemaVersion").asInt() != 1) {
                throw new MessageRejectedException("SCHEMA_UNSUPPORTED");
            }
            if (!node.path("aggregateVersion").isIntegralNumber()
                    || !node.path("aggregateVersion").canConvertToLong()) {
                throw new MessageRejectedException("INVALID_AGGREGATE_VERSION");
            }
            for (String name :
                    new String[] {
                        "eventId",
                        "sourceService",
                        "enterpriseId",
                        "warehouseId",
                        "eventType",
                        "aggregateId",
                        "occurredAt"
                    }) {
                if (!node.path(name).isString())
                    throw new MessageRejectedException("INVALID_MESSAGE_SCOPE");
            }
            if (node.hasNonNull("requestId") && !node.path("requestId").isString()) {
                throw new MessageRejectedException("INVALID_REQUEST_ID");
            }
            return JSON.treeToValue(node, RuntimeMessage.class);
        } catch (MessageRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException malformed) {
            throw new MessageRejectedException("INVALID_EVENT_PAYLOAD");
        }
    }

    /** 使用统一信封编码保持字段与摘要语义一致，生产者不能另造表示。 */
    public String encode() {
        return JSON.writeValueAsString(this);
    }

    /** 将消息来源和事件身份纳入去重作用域，避免不同来源事件互相覆盖。 */
    public String identity() {
        return hash(
                JSON.writeValueAsString(
                        List.of(sourceService, enterpriseId, warehouseId, eventId)));
    }

    /** 属性顺序与数字的JSON排版不应造成事件身份冲突；业务字符串仍按原值比较。 */
    public static String contentHash(String raw) {
        return hash(JSON.writeValueAsString(canonical(JSON.readTree(raw))));
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            var map = new TreeMap<String, Object>();
            node.properties()
                    .forEach(entry -> map.put(entry.getKey(), canonical(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            var list = new java.util.ArrayList<>();
            node.forEach(item -> list.add(canonical(item)));
            return list;
        }
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros();
        if (node.isBoolean()) return node.booleanValue();
        return node.isNull() ? null : node.asString();
    }

    /** 使用原有 UTF-8 与 SHA-256 规则生成摘要，重试键和持久化摘要必须一致。 */
    public static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
