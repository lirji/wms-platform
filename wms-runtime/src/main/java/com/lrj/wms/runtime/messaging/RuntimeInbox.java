package com.lrj.wms.runtime.messaging;

import com.lrj.wms.runtime.messaging.persistence.RuntimeInboxMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.MDC;

/** 入箱与业务处理分事务；业务效果、发出的回执Outbox、处理完成标记必须在同一个本库事务。 */
public final class RuntimeInbox implements KafkaInboxConsumer.DurableReceiver {
    @FunctionalInterface public interface Handler {
        /** 仅本库操作；远程副作用必须经Outbox，不能在持锁期间调用外部服务。 */
        void apply(SqlSession session, RuntimeMessage message);
    }
    private final SqlSessionFactory sessions;
    private final Map<String, String> sourceByTopic;
    private final Clock clock;

    public RuntimeInbox(SqlSessionFactory sessions, Map<String, String> sourceByTopic, Clock clock) {
        this.sessions = sessions;
        this.sourceByTopic = Map.copyOf(sourceByTopic);
        this.clock = clock;
    }

    @Override
    public void persist(ConsumerRecord<String, String> record) {
        String raw = record.value() == null ? "" : record.value();
        RuntimeMessage message = null;
        String error = null;
        try {
            message = RuntimeMessage.parse(raw);
            if (!message.sourceService().equals(sourceByTopic.get(record.topic()))) {
                throw new MessageRejectedException("UNTRUSTED_MESSAGE_SOURCE");
            }
        } catch (MessageRejectedException rejected) { error = rejected.code(); }
        String hash = error == null ? RuntimeMessage.contentHash(raw) : RuntimeMessage.hash(raw);
        try (SqlSession session = sessions.openSession(false)) {
            var mapper = session.getMapper(RuntimeInboxMapper.class);
            String identity = error == null ? message.identity() : null;
            if (identity != null) {
                var existing = mapper.lockIdentity(identity);
                if (existing != null) {
                    if (hash.equals(existing.get("payload_hash"))) { session.commit(); return; }
                    // 相同业务事件不同内容必须留隔离证据，不能以唯一键冲突吞掉篡改。
                    error = "EVENT_PAYLOAD_MISMATCH";
                    identity = null;
                }
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.randomUUID().toString()); row.put("eventKey", identity);
            row.put("enterpriseId", message == null ? null : message.enterpriseId());
            row.put("warehouseId", message == null ? null : message.warehouseId());
            row.put("sourceService", message == null ? null : message.sourceService());
            row.put("topic", record.topic()); row.put("partition", record.partition()); row.put("offset", record.offset());
            row.put("hash", hash); row.put("payload", raw.length() > 65536 && error != null ? raw.substring(0, 65536) : raw);
            row.put("status", error == null ? "PENDING" : "ISOLATED"); row.put("error", error); row.put("now", now());
            mapper.insert(row);
            if (identity != null) {
                var stored = mapper.lockIdentity(identity);
                if (stored == null) throw new IllegalStateException("Inbox身份未持久化");
                if (!hash.equals(stored.get("payload_hash"))) {
                    row.put("id", UUID.randomUUID().toString()); row.put("eventKey", null);
                    row.put("status", "ISOLATED"); row.put("error", "EVENT_PAYLOAD_MISMATCH");
                    mapper.insert(row);
                }
            }
            session.commit();
        }
    }

    /** 领取单条并处理，后台执行器必须限制每轮次数；返回false表示当前无到期任务。 */
    public boolean processNext(Handler handler) {
        String id;
        long epoch;
        try (SqlSession session = sessions.openSession(false)) {
            var mapper = session.getMapper(RuntimeInboxMapper.class);
            var row = mapper.lockNext(now());
            if (row == null) { session.commit(); return false; }
            id = String.valueOf(row.get("id"));
            epoch = ((Number) row.get("claim_epoch")).longValue() + 1;
            if (mapper.claim(id, epoch - 1, Timestamp.from(clock.instant().plusSeconds(30)), now()) != 1) {
                session.rollback(); return false;
            }
            session.commit();
        }
        long attempt = epoch;
        String previous = MDC.get("requestId");
        try (SqlSession session = sessions.openSession(false)) {
            var mapper = session.getMapper(RuntimeInboxMapper.class);
            var claimed = mapper.lockClaim(id, epoch);
            if (claimed == null) { session.rollback(); return true; }
            attempt = epoch - ((Number) claimed.get("retry_base_epoch")).longValue();
            if (attempt > 8) throw new MessageRejectedException("RETRY_EXHAUSTED");
            var message = validateTrusted(claimed);
            MDC.put("requestId", message.requestId() == null ? UUID.randomUUID().toString() : message.requestId());
            try {
                handler.apply(session, message);
                if (mapper.finish(id, epoch, "DONE", null, now(), now()) != 1) throw new IllegalStateException("消息处理代际冲突");
                session.commit(true);
            } catch (RuntimeException | Error failure) {
                // 回调可能通过同连接的其他持久化适配器写入，不能仅依赖MyBatis的dirty标记。
                session.rollback(true);
                throw failure;
            }
        } catch (RuntimeException failure) {
            // 上一个try-with-resources先回滚所有业务写，再以相同领取代际记录重试/隔离。
            boolean isolate = failure instanceof MessageRejectedException || attempt >= 8;
            String code = failure instanceof MessageRejectedException rejected ? rejected.code() : "PROCESSING_FAILED";
            long delay = Math.min(60, 1L << Math.min(attempt, 6));
            try (SqlSession session = sessions.openSession(false)) {
                session.getMapper(RuntimeInboxMapper.class).finish(id, epoch, isolate ? "ISOLATED" : "PENDING", code,
                        Timestamp.from(clock.instant().plusMillis(delay * 1000 + ThreadLocalRandom.current().nextInt(1000))), now());
                session.commit();
            }
        } finally {
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
        return true;
    }

    /** 人工重试和自动处理共用校验，无法证明受信来源的隔离记录不能重新投递。 */
    public RuntimeMessage validateTrusted(Map<String, Object> row) {
        var message = RuntimeMessage.parse(String.valueOf(row.get("payload")));
        if (!message.sourceService().equals(sourceByTopic.get(String.valueOf(row.get("topic_name"))))
                || !message.identity().equals(row.get("event_key"))) {
            throw new MessageRejectedException("UNTRUSTED_MESSAGE_SOURCE");
        }
        if (!RuntimeMessage.contentHash(String.valueOf(row.get("payload"))).equals(row.get("payload_hash"))) {
            throw new MessageRejectedException("EVENT_PAYLOAD_MISMATCH");
        }
        return message;
    }

    private Timestamp now() { return Timestamp.from(clock.instant()); }
}
