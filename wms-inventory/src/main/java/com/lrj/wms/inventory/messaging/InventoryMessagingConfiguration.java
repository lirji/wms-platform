package com.lrj.wms.inventory.messaging;

import com.lrj.wms.inventory.inventory.OutboxBudget;
import com.lrj.wms.inventory.inventory.OutboxPublisher;
import com.lrj.wms.inventory.inventory.domain.InventoryCodes;
import com.lrj.wms.inventory.query.InventoryProjectionService;
import com.lrj.wms.runtime.messaging.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式配置消息后启动真实发布与接收，不用进程内方法调用冒充消息链路。 */
@Configuration
@ConditionalOnProperty(name = "wms.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaSettings.class)
public class InventoryMessagingConfiguration {
    @Bean(destroyMethod = "close")
    KafkaMessagePublisher inventoryKafkaPublisher(KafkaSettings settings) {
        return new KafkaMessagePublisher(settings, "wms-inventory-outbox");
    }

    @Bean
    RuntimeInbox inventoryRuntimeInbox(SqlSessionFactory sessions, KafkaSettings settings) {
        return new RuntimeInbox(sessions, Map.of(settings.topicPrefix() + ".inventory.events", "wms-inventory"), Clock.systemUTC());
    }

    @Bean
    KafkaInboxConsumer inventoryKafkaInbox(KafkaSettings settings, RuntimeInbox inbox) {
        return new KafkaInboxConsumer(settings, settings.topicPrefix() + ".inventory-projection",
                List.of(settings.topicPrefix() + ".inventory.events"), inbox);
    }

    @Bean
    MessageWorker inventoryOutboxWorker(SqlSessionFactory sessions, KafkaSettings settings,
            KafkaMessagePublisher publisher, OutboxBudget budget) {
        var outbox = new OutboxPublisher(sessions, new InventoryEventTransport(sessions, publisher,
                settings.topicPrefix() + ".inventory.events"), Clock.systemUTC(), budget);
        return new MessageWorker("inventory-outbox", outbox::publishDue);
    }

    @Bean
    MessageWorker inventoryInboxWorker(RuntimeInbox inbox) {
        return new MessageWorker("inventory-inbox", () -> {
            for (int i = 0; i < 32 && !Thread.currentThread().isInterrupted(); i++) {
                if (!inbox.processNext((session, message) -> {
                    if (InventoryCodes.EVENT_RESERVATION_CONFIRMED.equals(message.eventType())) return;
                    if (!InventoryCodes.EVENT_BALANCE_CHANGED.equals(message.eventType())) throw new MessageRejectedException("UNSUPPORTED_EVENT_TYPE");
                    var payload = message.payload();
                    new InventoryProjectionService(session, Clock.systemUTC()).apply(message.enterpriseId(), message.warehouseId(),
                            message.eventId(), message.aggregateId(), message.aggregateVersion(), message.eventType(), payload.toString(),
                            Timestamp.from(Instant.parse(message.occurredAt())), payload.path("ownerId").asString(),
                            payload.path("locationId").asString(), payload.path("skuId").asString(), payload.path("lotId").asString(),
                            payload.path("qualityCode").asString());
                })) break;
            }
        });
    }
    @Bean(destroyMethod = "close")
    KafkaDependencyHealth inventoryMessagingHealth(KafkaSettings settings, KafkaInboxConsumer consumer,
            @org.springframework.beans.factory.annotation.Qualifier("inventoryOutboxWorker") MessageWorker outbox,
            @org.springframework.beans.factory.annotation.Qualifier("inventoryInboxWorker") MessageWorker inbox) {
        return new KafkaDependencyHealth(settings, () -> consumer.isRunning() && consumer.isReceiving()
                && outbox.lastPulseSucceeded() && inbox.lastPulseSucceeded());
    }
}
