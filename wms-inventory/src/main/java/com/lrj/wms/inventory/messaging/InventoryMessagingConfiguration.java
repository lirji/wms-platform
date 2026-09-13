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
    /** 监控独立线程有界采样，HTTP指标读取不触发数据库查询。 */
    @Bean
    MessageQueueMetrics inventoryQueueMetrics(SqlSessionFactory sessions, io.micrometer.core.instrument.MeterRegistry registry) {
        return new MessageQueueMetrics(sessions, registry, MessageQueueMetrics.Queue.INVENTORY_OUTBOX, Clock.systemUTC());
    }
    @Bean
    MessageWorker inventoryQueueMetricsWorker(MessageQueueMetrics metrics) {
        return new MessageWorker("inventory-queue-metrics", metrics::sampleDue);
    }
    /** 恢复仅访问本服务本库，可信Inbox规则沿用实际消费者配置。 */
    @Bean
    @ConditionalOnProperty(name = "wms.messaging.recovery-enabled", havingValue = "true")
    MessageRecoveryService inventoryMessageRecovery(SqlSessionFactory sessions, RuntimeInbox inbox) {
        return new MessageRecoveryService(sessions, MessageQueueMetrics.Queue.INVENTORY_OUTBOX, inbox, Clock.systemUTC());
    }
    @Bean(destroyMethod = "close")
    KafkaMessagePublisher inventoryKafkaPublisher(KafkaSettings settings) {
        return new KafkaMessagePublisher(settings, "wms-inventory-outbox");
    }

    @Bean
    RuntimeInbox inventoryRuntimeInbox(SqlSessionFactory sessions, KafkaSettings settings) {
        return new RuntimeInbox(sessions, Map.of(settings.topicPrefix() + ".inventory.events", "wms-inventory",
                settings.topicPrefix() + ".inbound.commands", "wms-inbound", settings.topicPrefix() + ".outbound.commands", "wms-outbound",
                settings.topicPrefix()+".transfer.commands","wms-fulfillment"), Clock.systemUTC());
    }

    /** RM按cell部署时不能继续共享无路由的消费组；明确配置在启动边界校验。 */
    @Bean
    InventoryCellRouting inventoryCellRouting(org.springframework.core.env.Environment env) {
        boolean rm=env.getProperty("wms.tcc.rm.enabled",Boolean.class,false);
        String cell=env.getProperty("wms.messaging.inventory-cell-id");
        String rmCell=rm?env.getRequiredProperty("wms.tcc.rm.cell-id"):null;
        if(cell==null||cell.isBlank()) cell=rmCell;
        if(rm&&!java.util.Objects.equals(cell,rmCell)) throw new IllegalArgumentException("消息cell与RM资源cell不一致");
        return new InventoryCellRouting(cell,env.getProperty("wms.messaging.inventory-routing-json"),rm);
    }
    @Bean
    KafkaInboxConsumer inventoryKafkaInbox(KafkaSettings settings, RuntimeInbox inbox, InventoryCellRouting routing) {
        return new KafkaInboxConsumer(settings, routing.group(settings.topicPrefix()),
                List.of(settings.topicPrefix() + ".inventory.events", settings.topicPrefix() + ".inbound.commands", settings.topicPrefix() + ".outbound.commands",settings.topicPrefix()+".transfer.commands"), routing.receiver(inbox));
    }

    @Bean
    MessageWorker inventoryOutboxWorker(SqlSessionFactory sessions, KafkaSettings settings,
            KafkaMessagePublisher publisher, OutboxBudget budget) {
        var outbox = new OutboxPublisher(sessions, new InventoryEventTransport(sessions, publisher,
                settings.topicPrefix()), Clock.systemUTC(), budget);
        return new MessageWorker("inventory-outbox", outbox::publishDue);
    }

    @Bean
    MessageWorker inventoryInboxWorker(RuntimeInbox inbox, InventoryCellRouting routing) {
        return new MessageWorker("inventory-inbox", () -> {
            for (int i = 0; i < 32 && !Thread.currentThread().isInterrupted(); i++) {
                if (!inbox.processNext((session, message) -> {
                    routing.requireLocal(session,message);
                    if("wms-fulfillment".equals(message.sourceService())) {
                        new com.lrj.wms.inventory.serial.SerialTransferCommandService(session,Clock.systemUTC()).accept(message);return;
                    }
                    if (java.util.Set.of("wms-inbound", "wms-outbound").contains(message.sourceService())) {
                        new StockCommandMessageHandler(Clock.systemUTC()).apply(session, message);
                        return;
                    }
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
