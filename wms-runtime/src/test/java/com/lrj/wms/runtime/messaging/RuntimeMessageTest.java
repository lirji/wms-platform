package com.lrj.wms.runtime.messaging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 信封控制字段必须保持类型与范围；不能由JSON宽松转换扩大受信边界。 */
class RuntimeMessageTest {
    @Test void rejectsCoercedIdentityAndUnsupportedVersions() {
        String valid = new RuntimeMessage(1, "E", "source", "ENT", "WH", "type", "AGG", 1,
                "2026-09-12T00:00:00Z", "123", RuntimeMessage.JSON.createObjectNode()).encode();
        assertEquals("E", RuntimeMessage.parse(valid).eventId());
        assertThrows(MessageRejectedException.class, () -> RuntimeMessage.parse(valid.replace("\"requestId\":\"123\"", "\"requestId\":123")));
        assertThrows(MessageRejectedException.class, () -> RuntimeMessage.parse(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(MessageRejectedException.class, () -> RuntimeMessage.parse(valid.replace("\"aggregateVersion\":1", "\"aggregateVersion\":1.5")));
        assertThrows(MessageRejectedException.class, () -> RuntimeMessage.parse(valid + "{}"));
        assertThrows(MessageRejectedException.class, () -> RuntimeMessage.parse(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297")));
    }
}
