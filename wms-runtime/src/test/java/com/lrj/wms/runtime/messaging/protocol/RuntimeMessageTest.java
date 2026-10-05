package com.lrj.wms.runtime.messaging.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 信封控制字段必须保持类型与范围；不能由JSON宽松转换扩大受信边界。 */
class RuntimeMessageTest {
    @Test
    void enforcesUtf8ByteBudgetIncludingEnvelopeAtTheBoundary() {
        var payload = RuntimeMessage.JSON.createObjectNode().put("text", "");
        var message =
                new RuntimeMessage(
                        1,
                        "E",
                        "source",
                        "ENT",
                        "WH",
                        "type",
                        "AGG",
                        1,
                        "2026-09-12T00:00:00Z",
                        null,
                        payload);
        int envelopeBytes =
                message.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        payload.put("text", "a".repeat(RuntimeMessage.MAX_PAYLOAD_BYTES - envelopeBytes));
        assertEquals(
                RuntimeMessage.MAX_PAYLOAD_BYTES,
                message.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("E", RuntimeMessage.parse(message.encode()).eventId());
        payload.put("text", payload.path("text").asString() + "仓");
        assertEquals(
                "MESSAGE_TOO_LARGE",
                assertThrows(
                                MessageRejectedException.class,
                                () -> RuntimeMessage.parse(message.encode()))
                        .code());
    }

    @Test
    void rejectsCoercedIdentityAndUnsupportedVersions() {
        String valid =
                new RuntimeMessage(
                                1,
                                "E",
                                "source",
                                "ENT",
                                "WH",
                                "type",
                                "AGG",
                                1,
                                "2026-09-12T00:00:00Z",
                                "123",
                                RuntimeMessage.JSON.createObjectNode())
                        .encode();
        assertEquals("E", RuntimeMessage.parse(valid).eventId());
        assertThrows(
                MessageRejectedException.class,
                () ->
                        RuntimeMessage.parse(
                                valid.replace("\"requestId\":\"123\"", "\"requestId\":123")));
        assertThrows(
                MessageRejectedException.class,
                () ->
                        RuntimeMessage.parse(
                                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(
                MessageRejectedException.class,
                () ->
                        RuntimeMessage.parse(
                                valid.replace(
                                        "\"aggregateVersion\":1", "\"aggregateVersion\":1.5")));
        assertThrows(MessageRejectedException.class, () -> RuntimeMessage.parse(valid + "{}"));
        assertThrows(
                MessageRejectedException.class,
                () ->
                        RuntimeMessage.parse(
                                valid.replace(
                                        "\"schemaVersion\":1", "\"schemaVersion\":4294967297")));
    }
}
