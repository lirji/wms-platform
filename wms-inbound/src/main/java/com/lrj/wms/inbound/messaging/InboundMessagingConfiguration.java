package com.lrj.wms.inbound.messaging;

import com.lrj.wms.inbound.protocol.SourceMapper;
import com.lrj.wms.inbound.receipt.InboundReceiptService;
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

/** 收货T1真实发布和库存回执T3真实消费；enabled必须同时具备数据库与消息配置。 */
@Configuration
@ConditionalOnProperty(name = "wms.messaging.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaSettings.class)
public class InboundMessagingConfiguration {
    /** 监控独立线程有界采样，HTTP指标读取不触发数据库查询。 */
    @Bean
    MessageQueueMetrics inboundQueueMetrics(SqlSessionFactory sessions, io.micrometer.core.instrument.MeterRegistry registry) {
        return new MessageQueueMetrics(sessions, registry, MessageQueueMetrics.Queue.SOURCE_OUTBOX, Clock.systemUTC());
    }
    @Bean
    MessageWorker inboundQueueMetricsWorker(MessageQueueMetrics metrics) {
        return new MessageWorker("inbound-queue-metrics", metrics::sampleDue);
    }
    @Bean(destroyMethod = "close")
    KafkaMessagePublisher inboundKafkaPublisher(KafkaSettings settings) {
        return new KafkaMessagePublisher(settings, "wms-inbound-outbox");
    }
    @Bean
    MessageWorker inboundOutboxWorker(SqlSessionFactory sessions, KafkaMessagePublisher publisher, KafkaSettings settings) {
        var outbox = new SourceOutboxPublisher(sessions, publisher, "wms-inbound", settings.topicPrefix(), Clock.systemUTC());
        return new MessageWorker("inbound-outbox", outbox::publishDue);
    }
    @Bean
    RuntimeInbox inboundRuntimeInbox(SqlSessionFactory sessions, KafkaSettings settings) {
        return new RuntimeInbox(sessions, Map.of(settings.topicPrefix() + ".inbound.results", "wms-inventory"), Clock.systemUTC());
    }
    @Bean
    KafkaInboxConsumer inboundKafkaInbox(KafkaSettings settings, RuntimeInbox inbox) {
        return new KafkaInboxConsumer(settings, settings.topicPrefix() + ".inbound-results",
                List.of(settings.topicPrefix() + ".inbound.results"), inbox);
    }
    @Bean
    MessageWorker inboundInboxWorker(RuntimeInbox inbox) {
        return new MessageWorker("inbound-inbox", () -> {
            for (int i = 0; i < 32 && !Thread.currentThread().isInterrupted(); i++) {
                if (!inbox.processNext((session, message) -> {
                    var body = message.payload();
                    if (!"InventoryCommandResult".equals(message.eventType()) || !"wms-inbound".equals(body.path("recipientService").asString())) {
                        throw new MessageRejectedException("UNSUPPORTED_RESULT_TYPE");
                    }
                    if (!body.path("commandId").isString() || !body.path("state").isString()) throw new MessageRejectedException("INVALID_COMMAND_RESULT");
                    String commandId = body.path("commandId").asString();
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
                    var service = new InboundReceiptService(session, Clock.systemUTC());
                    switch (String.valueOf(fact.get("action"))) {
                        case "RECEIVE" -> service.consumeReceive(message.enterpriseId(), message.warehouseId(), lineId, message.eventId(),
                                commandId, body.path("state").asString(), postingId, qty);
                        case "PUTAWAY" -> service.consumePutaway(message.enterpriseId(), message.warehouseId(), lineId, message.eventId(),
                                commandId, body.path("state").asString(), postingId, qty);
                        default -> throw new MessageRejectedException("UNSUPPORTED_RESULT_ACTION");
                    }
                })) break;
            }
        });
    }
    @Bean(destroyMethod = "close")
    KafkaDependencyHealth inboundMessagingHealth(KafkaSettings settings, KafkaInboxConsumer consumer,
            @org.springframework.beans.factory.annotation.Qualifier("inboundOutboxWorker") MessageWorker outbox,
            @org.springframework.beans.factory.annotation.Qualifier("inboundInboxWorker") MessageWorker inbox) {
        return new KafkaDependencyHealth(settings, () -> consumer.isReceiving() && outbox.lastPulseSucceeded() && inbox.lastPulseSucceeded());
    }
}
