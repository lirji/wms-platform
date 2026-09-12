package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.messaging.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式启用的库存确认接收，消息落Inbox后才提交Kafka位点；真实TC观察仍由独立恢复负责。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="wms.messaging.enabled",havingValue="true")
@EnableConfigurationProperties(KafkaSettings.class)
public class FulfillmentMessagingConfiguration {
    @Bean
    RuntimeInbox fulfillmentRuntimeInbox(SqlSessionFactory sessions, KafkaSettings settings) {
        return new RuntimeInbox(sessions, Map.of(settings.topicPrefix()+".fulfillment.results", "wms-inventory"), Clock.systemUTC());
    }
    @Bean
    KafkaInboxConsumer fulfillmentKafkaInbox(KafkaSettings settings, RuntimeInbox inbox) {
        return new KafkaInboxConsumer(settings, settings.topicPrefix()+".fulfillment-results",
                List.of(settings.topicPrefix()+".fulfillment.results"), inbox);
    }
    @Bean
    MessageWorker fulfillmentInboxWorker(RuntimeInbox inbox) {
        var handler = new ReservationConfirmationHandler(Clock.systemUTC());
        return new MessageWorker("fulfillment-inbox", () -> {
            long deadline = System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
            for (int i=0; i<32 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted(); i++) {
                if (!inbox.processNext(handler)) break;
            }
        });
    }
    @Bean
    MessageQueueMetrics fulfillmentQueueMetrics(SqlSessionFactory sessions, io.micrometer.core.instrument.MeterRegistry registry) {
        return new MessageQueueMetrics(sessions,registry,MessageQueueMetrics.Queue.FULFILLMENT_OUTBOX,Clock.systemUTC());
    }
    @Bean
    MessageWorker fulfillmentQueueMetricsWorker(MessageQueueMetrics metrics) {
        return new MessageWorker("fulfillment-queue-metrics",metrics::sampleDue);
    }
    @Bean
    @ConditionalOnProperty(name="wms.messaging.recovery-enabled",havingValue="true")
    MessageRecoveryService fulfillmentMessageRecovery(SqlSessionFactory sessions,RuntimeInbox inbox) {
        return new MessageRecoveryService(sessions,MessageQueueMetrics.Queue.FULFILLMENT_OUTBOX,inbox,Clock.systemUTC());
    }
    @Bean(destroyMethod="close")
    KafkaDependencyHealth fulfillmentMessagingHealth(KafkaSettings settings,KafkaInboxConsumer consumer,
            @org.springframework.beans.factory.annotation.Qualifier("fulfillmentInboxWorker") MessageWorker inbox) {
        return new KafkaDependencyHealth(settings,()->consumer.isReceiving() && inbox.lastPulseSucceeded());
    }
}
