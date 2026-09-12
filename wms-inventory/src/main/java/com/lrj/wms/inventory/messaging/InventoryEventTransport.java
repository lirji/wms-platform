package com.lrj.wms.inventory.messaging;

import com.lrj.wms.inventory.inventory.*;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.inventory.infrastructure.InventoryMapper;
import com.lrj.wms.runtime.messaging.*;
import org.apache.ibatis.session.SqlSessionFactory;
import tools.jackson.databind.node.ObjectNode;

/** 使用原始落账时刻与不可变桶维度发送库存事件，查询数据库后先释放连接再等待网络。 */
public final class InventoryEventTransport implements OutboxTransport {
    private final SqlSessionFactory sessions;
    private final KafkaMessagePublisher publisher;
    private final String topic;

    public InventoryEventTransport(SqlSessionFactory sessions, KafkaMessagePublisher publisher, String topic) {
        this.sessions = sessions; this.publisher = publisher; this.topic = topic;
    }

    @Override public void publish(OutboxRecord record) {
        var node = RuntimeMessage.JSON.readTree(record.payload());
        if (!(node instanceof ObjectNode payload) || record.occurredAt() == null) throw new OutboxIsolateException("无有效事件正文或原始时刻");
        if (InventoryCodes.EVENT_BALANCE_CHANGED.equals(record.eventType())) {
            try (var session = sessions.openSession(false)) {
                var bucket = session.getMapper(InventoryMapper.class).lockBalanceById(record.enterpriseId(), record.warehouseId(), record.aggregateId());
                if (bucket == null) throw new OutboxIsolateException("库存事件缺少权威桶维度");
                for (String[] field : new String[][] {{"ownerId", "owner_id"}, {"locationId", "location_id"},
                        {"skuId", "sku_id"}, {"lotId", "lot_id"}, {"qualityCode", "quality_code"}}) {
                    payload.put(field[0], String.valueOf(bucket.get(field[1])));
                }
                session.commit();
            }
        }
        // 历史事件无请求ID时用事件本身稳定关联，不能在每次重发生成不同内容。
        String requestId = payload.has("requestId") ? payload.path("requestId").asString() : record.eventId();
        var message = new RuntimeMessage(1, record.eventId(), "wms-inventory", record.enterpriseId(), record.warehouseId(),
                record.eventType(), record.aggregateId(), record.aggregateVersion(), record.occurredAt().toString(), requestId, payload);
        publisher.publish(topic, RuntimeMessage.hash(record.enterpriseId() + "/" + record.warehouseId() + "/" + record.aggregateId()), message.encode());
    }
}
