package com.lrj.wms.runtime.messaging;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.SmartLifecycle;
import org.slf4j.LoggerFactory;

/** Kafka只负责投递到持久化Inbox；业务重试由库内有界队列执行，不能卡住后续依赖事件。 */
public final class KafkaInboxConsumer implements SmartLifecycle {
    /** 返回之前必须提交Inbox（包括毒消息隔离）；禁止仅在内存去重后返回。 */
    @FunctionalInterface public interface DurableReceiver { void persist(ConsumerRecord<String, String> record); }
    private final KafkaSettings settings;
    private final String group;
    private final List<String> topics;
    private final DurableReceiver receiver;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private Thread worker;
    private volatile boolean receiving;

    public KafkaInboxConsumer(KafkaSettings settings, String group, List<String> topics, DurableReceiver receiver) {
        this.settings = settings;
        this.group = group;
        this.topics = List.copyOf(topics);
        this.receiver = receiver;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        worker = Thread.ofPlatform().name(group + "-inbox").unstarted(this::receive);
        worker.start();
    }

    private void receive() {
        while (running) {
            try { consumeUntilFailure(); }
            catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Inbox连接中断，等待恢复，group={}", group);
            } finally { receiving = false; consumer = null; }
            if (running) {
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        running = false;
    }

    private void consumeUntilFailure() {
        var properties = settings.connection();
        properties.put("group.id", group);
        properties.put("enable.auto.commit", "false");
        properties.put("auto.offset.reset", "earliest");
        properties.put("allow.auto.create.topics", "false");
        properties.put("max.poll.records", "32");
        properties.put("fetch.max.bytes", "1048576");
        properties.put("max.partition.fetch.bytes", "524288");
        properties.put("max.poll.interval.ms", "300000");
        properties.put("default.api.timeout.ms", "5000");
        properties.put("isolation.level", "read_committed");
        var client = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
        try {
            consumer = client;
            client.subscribe(topics);
            while (running) {
                var failed = new HashSet<TopicPartition>();
                var records = client.poll(Duration.ofMillis(250));
                receiving = !client.groupMetadata().memberId().isBlank();
                for (var record : records) {
                    if (!running) break;
                    var partition = new TopicPartition(record.topic(), record.partition());
                    if (failed.contains(partition)) continue;
                    try {
                        receiver.persist(record);
                        // 数据库提交后再提交位点；崩溃窗口由Inbox唯一键吸收重复。
                        client.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(3));
                    } catch (RuntimeException unavailable) {
                        client.seek(partition, record.offset());
                        failed.add(partition);
                        LoggerFactory.getLogger(getClass()).warn("Inbox持久化或位点确认失败，保留原位点重试，group={}", group);
                    }
                }
                if (!failed.isEmpty()) {
                    try { Thread.sleep(250); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        } catch (WakeupException shutdown) {
            if (running) LoggerFactory.getLogger(getClass()).error("Inbox消费者意外停止，group={}", group);
        } finally { receiving = false; client.close(Duration.ofSeconds(3)); consumer = null; }
    }

    /** 供就绪/监控区分线程存活与已加入消费者组（空分配的正常副本也可就绪）。 */
    public boolean isReceiving() { return receiving; }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public synchronized void stop() {
        running = false;
        var client = consumer;
        if (client != null) client.wakeup();
        if (worker != null) {
            try { worker.join(15000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
