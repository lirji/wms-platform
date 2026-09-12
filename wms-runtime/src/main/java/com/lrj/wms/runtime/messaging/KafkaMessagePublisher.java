package com.lrj.wms.runtime.messaging;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/** 发布确认只证明broker接受；业务状态由消费库事务及回执决定。 */
public final class KafkaMessagePublisher implements AutoCloseable {
    public static final int MAX_PAYLOAD_BYTES = 262144;
    private final KafkaProducer<String, String> producer;

    public KafkaMessagePublisher(KafkaSettings settings, String clientId) {
        var properties = settings.connection();
        properties.put("client.id", clientId);
        properties.put("acks", "all");
        properties.put("enable.idempotence", "true");
        properties.put("max.in.flight.requests.per.connection", "5");
        properties.put("delivery.timeout.ms", "5000");
        properties.put("request.timeout.ms", "3000");
        properties.put("max.block.ms", "1000");
        properties.put("linger.ms", "0");
        properties.put("buffer.memory", "4194304");
        properties.put("max.request.size", "524288");
        producer = new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());
    }

    /** 相同聚合使用相同分区键；数据库Outbox还必须防止同聚合多个发布者倒序发送。 */
    public void publish(String topic, String partitionKey, String json) {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("消息正文超出预算");
        }
        try {
            producer.send(new ProducerRecord<>(topic, partitionKey, json)).get(6, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发布中断，结果未知，需要按原事件身份恢复", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
            throw new IllegalStateException("发布未获确认，保留原事件重试", unavailable);
        }
    }

    @Override public void close() { producer.close(Duration.ofSeconds(3)); }
}
