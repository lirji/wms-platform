package com.lrj.wms.inventory.inventory;

/** 一次待投递库存事件。发布器按 eventId 幂等。 */
public record OutboxRecord(String eventId, String enterpriseId, String warehouseId, String aggregateType,
        String aggregateId, long aggregateVersion, String eventType, String operationId, String payload) {
}
