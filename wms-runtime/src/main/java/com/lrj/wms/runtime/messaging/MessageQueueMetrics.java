package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.MessageQueueMetricsMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;

/** 后台有界采样；指标HTTP只读取不可变快照，不在抓取线程中占数据库连接。 */
public final class MessageQueueMetrics {
    public enum Queue { INBOX, INVENTORY_OUTBOX, SOURCE_OUTBOX, FULFILLMENT_OUTBOX }
    private static final List<String> STATES = List.of("PENDING", "CLAIMED", "ISOLATED");
    private static final int DEPTH_LIMIT = 1000;
    private final SqlSessionFactory sessions;
    private final List<Queue> queues;
    private final Clock clock;
    private volatile Snapshot snapshot = new Snapshot(null, Map.of());
    private Instant nextAttempt = Instant.MIN;

    public MessageQueueMetrics(SqlSessionFactory sessions, MeterRegistry registry, Queue outbox, Clock clock) {
        if (outbox == Queue.INBOX) throw new IllegalArgumentException("必须明确当前服务的Outbox类别");
        this.sessions = sessions;
        this.queues = List.of(Queue.INBOX, outbox);
        this.clock = clock;
        for (var queue : queues) for (String state : STATES) {
            String key = queue + "/" + state;
            Gauge.builder("wms.messaging.backlog", this, metrics -> metrics.depth(key))
                    .description("本库待处理数量，1001表示至少1001条")
                    .tags("queue", queue.name(), "state", state).register(registry);
            Gauge.builder("wms.messaging.backlog.capped", this, metrics -> metrics.depth(key) > DEPTH_LIMIT ? 1 : 0)
                    .description("积压计数是否达到采样上限，需结合快照有效性")
                    .tags("queue", queue.name(), "state", state).register(registry);
            Gauge.builder("wms.messaging.oldest.age", this, metrics -> metrics.oldestAge(key))
                    .baseUnit("seconds").description("该状态最旧消息的等待秒数")
                    .tags("queue", queue.name(), "state", state).register(registry);
        }
        Gauge.builder("wms.messaging.sample.age", this, MessageQueueMetrics::sampleAge)
                .baseUnit("seconds").description("距最近完整成功采样的秒数，启动未采样为NaN").register(registry);
        Gauge.builder("wms.messaging.sample.available", this, metrics -> metrics.snapshot.at() == null ? 0 : 1)
                .description("是否至少成功采样过一次；失效请同时检查sample.age").register(registry);
    }

    /** 最频繁5秒一次；失败也退避，不因抓取频率或数据库故障加速查询。 */
    public synchronized void sampleDue() {
        Instant now = clock.instant();
        if (now.isBefore(nextAttempt)) return;
        nextAttempt = now.plusSeconds(5);
        Map<String, Value> values = new LinkedHashMap<>();
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(MessageQueueMetricsMapper.class);
            for (var queue : queues) for (String state : STATES) {
                int depth = mapper.boundedDepth(queue.name(), state);
                Long micros = mapper.oldestAgeMicros(queue.name(), state, Timestamp.from(now));
                values.put(queue + "/" + state, new Value(depth, micros == null ? 0 : Math.max(0, micros / 1_000_000.0)));
            }
        }
        // 任一查询失败保留旧快照，sample.age持续增长；不能用零值伪装无积压。
        snapshot = new Snapshot(now, Map.copyOf(values));
    }

    private double depth(String key) {
        Value value = snapshot.values().get(key);
        return value == null ? Double.NaN : value.depth();
    }

    private double oldestAge(String key) {
        Snapshot current = snapshot;
        Value value = current.values().get(key);
        if (value == null) return Double.NaN;
        return value.depth() == 0 ? 0 : value.oldestSeconds() + Math.max(0, java.time.Duration.between(current.at(), clock.instant()).toMillis() / 1000.0);
    }

    private double sampleAge() {
        Instant at = snapshot.at();
        return at == null ? Double.NaN : Math.max(0, java.time.Duration.between(at, clock.instant()).toMillis() / 1000.0);
    }

    private record Value(int depth, double oldestSeconds) { }
    private record Snapshot(Instant at, Map<String, Value> values) { }
}
