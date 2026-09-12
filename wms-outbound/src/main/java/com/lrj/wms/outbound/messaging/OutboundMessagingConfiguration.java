package com.lrj.wms.outbound.messaging;

import com.lrj.wms.outbound.protocol.SourceMapper;
import com.lrj.wms.outbound.order.OutboundOrderService;
import com.lrj.wms.runtime.messaging.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 出库T1真实发布和库存回执T3真实消费；enabled必须同时具备数据库与消息配置。 */
@Configuration
@ConditionalOnProperty(name = "wms.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaSettings.class)
public class OutboundMessagingConfiguration {
    /** 监控独立线程有界采样，HTTP指标读取不触发数据库查询。 */
    @Bean
    MessageQueueMetrics outboundQueueMetrics(SqlSessionFactory sessions, io.micrometer.core.instrument.MeterRegistry registry) {
        return new MessageQueueMetrics(sessions, registry, MessageQueueMetrics.Queue.SOURCE_OUTBOX, Clock.systemUTC());
    }
    @Bean
    MessageWorker outboundQueueMetricsWorker(MessageQueueMetrics metrics) {
        return new MessageWorker("outbound-queue-metrics", metrics::sampleDue);
    }
    /** 恢复仅访问本服务本库，可信Inbox规则沿用实际消费者配置。 */
    @Bean
    @ConditionalOnProperty(name = "wms.messaging.recovery-enabled", havingValue = "true")
    MessageRecoveryService outboundMessageRecovery(SqlSessionFactory sessions, RuntimeInbox inbox) {
        return new MessageRecoveryService(sessions, MessageQueueMetrics.Queue.SOURCE_OUTBOX, inbox, Clock.systemUTC());
    }
    @Bean(destroyMethod = "close")
    KafkaMessagePublisher outboundKafkaPublisher(KafkaSettings settings) {
        return new KafkaMessagePublisher(settings, "wms-outbound-outbox");
    }
    @Bean
    MessageWorker outboundOutboxWorker(SqlSessionFactory sessions, KafkaMessagePublisher publisher, KafkaSettings settings) {
        var outbox = new SourceOutboxPublisher(sessions, publisher, "wms-outbound", settings.topicPrefix(), Clock.systemUTC());
        return new MessageWorker("outbound-outbox", outbox::publishDue);
    }
    @Bean
    RuntimeInbox outboundRuntimeInbox(SqlSessionFactory sessions, KafkaSettings settings) {
        return new RuntimeInbox(sessions, Map.of(settings.topicPrefix() + ".outbound.results", "wms-inventory"), Clock.systemUTC());
    }
    @Bean
    KafkaInboxConsumer outboundKafkaInbox(KafkaSettings settings, RuntimeInbox inbox) {
        return new KafkaInboxConsumer(settings, settings.topicPrefix() + ".outbound-results",
                List.of(settings.topicPrefix() + ".outbound.results"), inbox);
    }
    @Bean
    MessageWorker outboundInboxWorker(RuntimeInbox inbox) {
        return new MessageWorker("outbound-inbox", () -> {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            for (int i = 0; i < 32 && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted(); i++) {
                if (!inbox.processNext((session, message) -> {
                    var body = message.payload();
                    if (!"InventoryCommandResult".equals(message.eventType()) || !"wms-outbound".equals(body.path("recipientService").asString())) {
                        throw new MessageRejectedException("UNSUPPORTED_RESULT_TYPE");
                    }
                    if (!body.path("commandId").isString() || !body.path("state").isString()) throw new MessageRejectedException("INVALID_COMMAND_RESULT");
                    String commandId = body.path("commandId").asString();
                    if (!commandId.equals(message.aggregateId()) || message.aggregateVersion() != 1)
                        throw new MessageRejectedException("RESULT_ENVELOPE_MISMATCH");
                    var fact = session.getMapper(SourceMapper.class).commandFact(message.enterpriseId(), message.warehouseId(), commandId);
                    if (fact == null) throw new MessageRejectedException("RESULT_FACT_MISSING");
                    BigDecimal qty;
                    try {
                        if (!body.path("postedQty").isNumber() && !body.path("postedQty").isString()) throw new IllegalArgumentException();
                        qty = new BigDecimal(body.path("postedQty").asString());
                    } catch (RuntimeException invalid) { throw new MessageRejectedException("INVALID_COMMAND_RESULT"); }
                    if (body.hasNonNull("postingId") && !body.path("postingId").isString()) throw new MessageRejectedException("INVALID_COMMAND_RESULT");
                    String postingId = body.hasNonNull("postingId") ? body.path("postingId").asString() : null;
                    String lineId = String.valueOf(fact.get("fact_line_id"));
                    var service = new OutboundOrderService(session, Clock.systemUTC());
                    switch (String.valueOf(fact.get("action"))) {
                        case "PICK" -> service.consumePick(message.enterpriseId(), message.warehouseId(), lineId, message.eventId(),
                                commandId, body.path("state").asString(), postingId, qty);
                        case "SHIP" -> service.consumeShip(message.enterpriseId(), message.warehouseId(), lineId, message.eventId(),
                                commandId, body.path("state").asString(), postingId, qty);
                        case "CANCEL" -> service.consumeCancel(message.enterpriseId(), message.warehouseId(), lineId, message.eventId(),
                                commandId, body.path("state").asString(), postingId, qty);
                        default -> throw new MessageRejectedException("UNSUPPORTED_RESULT_ACTION");
                    }
                })) break;
            }
        });
    }
    @Bean(destroyMethod = "close")
    KafkaDependencyHealth outboundMessagingHealth(KafkaSettings settings, KafkaInboxConsumer consumer,
            @org.springframework.beans.factory.annotation.Qualifier("outboundOutboxWorker") MessageWorker outbox,
            @org.springframework.beans.factory.annotation.Qualifier("outboundInboxWorker") MessageWorker inbox) {
        return new KafkaDependencyHealth(settings, () -> consumer.isReceiving() && outbox.lastPulseSucceeded() && inbox.lastPulseSucceeded());
    }
}
