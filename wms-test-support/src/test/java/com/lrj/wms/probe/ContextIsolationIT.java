package com.lrj.wms.probe;

import com.xxl.job.core.handler.IJobHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import static org.junit.jupiter.api.Assertions.*;

/** AC-44 行为探针：线程池与 Kafka 不得把 TCC XID 带进非预占链路。不是正式 Outbox 或履约服务。 */
class ContextIsolationIT {
    private static KafkaContainer kafka;

    @BeforeAll
    static void prepare() {
        kafka = new KafkaContainer("apache/kafka:3.8.0");
        kafka.start();
    }

    @AfterAll
    static void cleanup() {
        if (kafka != null) kafka.stop();
        RootContext.unbind();
    }

    /** 单线程池复用会保留 ThreadLocal XID；正式非TCC任务必须 finally unbind。 */
    @Test
    void threadPoolReusesXidUnlessUnbound() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> RootContext.bind("pool-leak-xid")).get();
            assertEquals("pool-leak-xid", pool.submit(RootContext::getXID).get());
            pool.submit(() -> {
                try {
                    RootContext.bind("temporary-xid");
                } finally {
                    RootContext.unbind();
                }
            }).get();
            assertNull(pool.submit(RootContext::getXID).get());
        }
    }

    /** 消息体可带 xid 审计字段；消费线程不得 bind 成全局事务上下文。 */
    @Test
    void kafkaConsumerDoesNotBindPayloadXid() throws Exception {
        var topic = "wms.probe.outbox";
        var bootstrap = kafka.getBootstrapServers();
        try (var admin = AdminClient.create(java.util.Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        var producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        try (var producer = new KafkaProducer<String, String>(producerProps)) {
            producer.send(new ProducerRecord<>(topic, "warehouse-A", "xid=192.168.0.1:8091:999")).get();
        }
        var consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "wms-probe-outbox");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        var seen = new CopyOnWriteArrayList<String>();
        try (var consumer = new KafkaConsumer<String, String>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    assertNull(RootContext.getXID());
                    assertFalse(record.value().isBlank());
                    seen.add(record.value());
                    consumer.commitSync();
                }
            }
        }
        assertEquals(List.of("xid=192.168.0.1:8091:999"), seen);
        assertNull(RootContext.getXID());
    }

    /** XXL 执行线程清理上下文后不得对未知 XID 发起二阶段。 */
    @Test
    void xxlHandlerMustNotDecideTcc() throws Exception {
        RootContext.bind("xxl-must-not-keep");
        IJobHandler handler = new IJobHandler() {
            @Override
            public void execute() {
                RootContext.unbind();
                assertNull(RootContext.getXID());
                assertNull(GlobalTransactionContext.getCurrent());
            }
        };
        try {
            handler.execute();
        } finally {
            RootContext.unbind();
        }
        assertNull(RootContext.getXID());
    }

    /** inbound/outbound/integration 不引入 Seata；inventory 仅 TCC RM 且禁用 AT 数据源代理。 */
    @Test
    void businessServicesDoNotEnableAtOrXa() throws Exception {
        for (String module : List.of("wms-inbound", "wms-outbound", "wms-integration")) {
            var pom = Files.readString(Path.of("..", module, "pom.xml"));
            assertFalse(pom.contains("seata"), module + " 不应依赖 Seata");
            assertFalse(pom.contains("atomikos") || pom.contains("narayana"), module + " 不应引入 XA");
        }
        var inventoryPom = Files.readString(Path.of("..", "wms-inventory", "pom.xml"));
        assertTrue(inventoryPom.contains("seata-all"), "inventory RM 需要 seata-all");
        assertFalse(inventoryPom.contains("atomikos") || inventoryPom.contains("narayana"), "inventory 不应引入 XA");
        var inventoryYml = Files.readString(Path.of("..", "wms-inventory", "src/main/resources/application.yml"));
        assertTrue(inventoryYml.contains("enable-auto-data-source-proxy: false"));
        assertFalse(inventoryYml.contains("enable-auto-data-source-proxy: true"));
        assertTrue(Class.forName("org.apache.seata.rm.datasource.DataSourceProxy")
                .getName().startsWith("org.apache.seata"));
        assertNull(GlobalTransactionContext.getCurrent());
    }
}
