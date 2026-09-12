package com.lrj.wms.runtime.messaging;

import com.lrj.wms.contract.messaging.StockPostingContext;
import com.lrj.wms.runtime.messaging.persistence.SourceOutboxMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ibatis.session.SqlSessionFactory;
import tools.jackson.databind.node.ObjectNode;

/** 来源T1发布：每条领取单独提交、释放连接后等待broker确认，最多每轮32条。 */
public final class SourceOutboxPublisher {
    private final SqlSessionFactory sessions;
    private final KafkaMessagePublisher publisher;
    private final String sourceService;
    private final String topic;
    private final Clock clock;

    public SourceOutboxPublisher(SqlSessionFactory sessions, KafkaMessagePublisher publisher,
            String sourceService, String topicPrefix, Clock clock) {
        if (!java.util.Set.of("wms-inbound", "wms-outbound").contains(sourceService)) throw new IllegalArgumentException("不支持的来源");
        this.sessions = sessions; this.publisher = publisher; this.sourceService = sourceService; this.clock = clock;
        this.topic = topicPrefix + "." + sourceService.substring(4) + ".commands";
    }

    /** 只有确认并成功更新本库代际才计为已发布，进程中断后由租约恢复。 */
    public int publishDue() {
        int published = 0;
        for (int i = 0; i < 32 && !Thread.currentThread().isInterrupted(); i++) {
            Map<String, Object> event;
            long epoch;
            try (var session = sessions.openSession(false)) {
                var mapper = session.getMapper(SourceOutboxMapper.class);
                event = mapper.lockNext(now());
                if (event == null) { session.commit(); break; }
                epoch = ((Number) event.get("claim_epoch")).longValue();
                if (mapper.claim(text(event, "event_id"), epoch, now(), Timestamp.from(clock.instant().plusSeconds(30))) != 1) {
                    session.rollback(); continue;
                }
                epoch++;
                session.commit();
            }
            long attempt = epoch - ((Number) event.get("retry_base_epoch")).longValue();
            try {
                if (attempt > 8) throw new MessageRejectedException("RETRY_EXHAUSTED");
                RuntimeMessage message = message(event);
                publisher.publish(topic, RuntimeMessage.hash(message.enterpriseId() + "/" + message.warehouseId() + "/" + message.aggregateId()), message.encode());
                if (finish(event, epoch, "PUBLISHED", null, clock.instant())) published++;
            } catch (MessageRejectedException invalid) {
                finish(event, epoch, "ISOLATED", invalid.code(), clock.instant());
            } catch (RuntimeException unavailable) {
                long delay = Math.min(60, 1L << Math.min(6, attempt));
                finish(event, epoch, "PENDING", "PUBLISH_UNCONFIRMED",
                        clock.instant().plusMillis(delay * 1000 + ThreadLocalRandom.current().nextInt(1000)));
            }
        }
        return published;
    }

    private RuntimeMessage message(Map<String, Object> event) {
        ObjectNode body;
        StockPostingContext context;
        try {
            body = (ObjectNode) RuntimeMessage.JSON.readTree(text(event, "payload"));
            if (!body.has("postingContext") || !body.path("schemaVersion").isIntegralNumber() || body.path("schemaVersion").intValue() != 1) {
                throw new MessageRejectedException("LEGACY_COMMAND_CONTEXT_MISSING");
            }
            if (!RuntimeMessage.contentHash(body.path("postingContext").toString()).equals(body.path("postingContextDigest").asString())) {
                throw new MessageRejectedException("POSTING_CONTEXT_MISMATCH");
            }
            context = RuntimeMessage.JSON.treeToValue(body.path("postingContext"), StockPostingContext.class);
        } catch (MessageRejectedException rejected) { throw rejected; }
        catch (RuntimeException malformed) { throw new MessageRejectedException("INVALID_COMMAND_CONTEXT"); }
        String enterprise = text(event, "enterprise_id"), warehouse = text(event, "warehouse_id"), commandId = text(event, "command_id");
        if (!body.path("commandId").isString() || !commandId.equals(body.path("commandId").asString())) {
            throw new MessageRejectedException("SOURCE_COMMAND_IDENTITY_MISMATCH");
        }
        Map<String, Object> metadata;
        try (var session = sessions.openSession()) {
            metadata = session.getMapper(SourceOutboxMapper.class).metadata(enterprise, warehouse, commandId);
        }
        if (metadata == null || !sourceService.equals(metadata.get("source_service"))) throw new MessageRejectedException("SOURCE_FACT_MISSING");
        if (metadata.get("safe_close_id") != null || !commandId.equals(metadata.get("active_command_id"))) {
            throw new MessageRejectedException("STALE_EXECUTION_ATTEMPT");
        }
        try { context.requireForAction(text(metadata, "action")); }
        catch (IllegalArgumentException invalid) { throw new MessageRejectedException("INVALID_COMMAND_CONTEXT"); }
        for (String[] field : new String[][] {{"action", "action"}, {"factParentId", "fact_parent_id"}, {"factPartId", "fact_part_id"},
                {"factLineId", "fact_line_id"}, {"sourceExecutionId", "source_execution_id"}, {"actorId", "actor_id"}}) {
            body.put(field[0], text(metadata, field[1]));
        }
        if (metadata.get("previous_command_id") != null) body.put("previousCommandId", text(metadata, "previous_command_id"));
        Instant occurred = instant(event.get("created_at"));
        return new RuntimeMessage(1, text(event, "event_id"), sourceService, enterprise, warehouse,
                text(event, "event_type"), text(metadata, "business_effect_key"), ((Number) metadata.get("attempt_no")).longValue(), occurred.toString(),
                body.path("requestId").asString(), body);
    }

    private boolean finish(Map<String, Object> event, long epoch, String status, String error, Instant next) {
        try (var session = sessions.openSession(false)) {
            int updated = session.getMapper(SourceOutboxMapper.class).finish(text(event, "event_id"), epoch, status, error, now(), Timestamp.from(next));
            session.commit(); return updated == 1;
        }
    }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) throw new MessageRejectedException("SOURCE_FACT_MISSING");
        return String.valueOf(value);
    }
    /** 数据库时间策略在JDBC边界完成转换；没有来源的墙钟值不得发布成瞬时事件。 */
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return com.lrj.wms.runtime.db.DatabaseInstants.require(local);
        throw new MessageRejectedException("SOURCE_TIME_MISSING");
    }
}
