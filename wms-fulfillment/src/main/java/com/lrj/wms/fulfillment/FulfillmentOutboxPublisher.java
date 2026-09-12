package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.messaging.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ibatis.session.SqlSessionFactory;

/** 屏障Outbox真实发布，原消息身份与快照不变；未知确认只允许按原事件恢复。 */
public final class FulfillmentOutboxPublisher {
    private final SqlSessionFactory sessions;
    private final KafkaMessagePublisher publisher;
    private final String prefix;
    private final Clock clock;
    public FulfillmentOutboxPublisher(SqlSessionFactory sessions,KafkaMessagePublisher publisher,String prefix,Clock clock) {
        this.sessions=sessions;this.publisher=publisher;this.prefix=prefix;this.clock=clock;
    }

    /** 每轮最多32项/20秒新领取预算；逐项领取后释放数据库连接再等待broker。 */
    public int publishDue() {
        int published=0;
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        for (int i=0;i<32 && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();i++) {
            Map<String,Object> row; long epoch;
            try (var session=sessions.openSession(false)) {
                var mapper=session.getMapper(FulfillmentOutboxMapper.class);
                row=mapper.lockNext(now());
                if (row==null) {session.commit();break;}
                epoch=((Number)row.get("claim_epoch")).longValue();
                if (mapper.claim(text(row,"event_id"),epoch,now(),Timestamp.from(clock.instant().plusSeconds(30)))!=1) {
                    session.rollback();continue;
                }
                epoch++;session.commit();
            }
            long attempt=epoch-((Number)row.get("retry_base_epoch")).longValue();
            try {
                if (attempt>8) throw new MessageRejectedException("RETRY_EXHAUSTED");
                var message=message(row);
                String encoded=message.encode();
                if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>KafkaMessagePublisher.MAX_PAYLOAD_BYTES)
                    throw new MessageRejectedException("MESSAGE_TOO_LARGE");
                String topic=prefix+(FulfillmentService.EVENT_ALLOCATION_COMPLETED.equals(message.eventType())
                        ? ".fulfillment.events" : ".outbound.authorizations");
                publisher.publish(topic,RuntimeMessage.hash(RuntimeMessage.JSON.writeValueAsString(
                        java.util.List.of(message.enterpriseId(),message.warehouseId(),message.aggregateId()))),encoded);
                if (finish(row,epoch,"PUBLISHED",null,clock.instant())) published++;
            } catch (MessageRejectedException invalid) {
                finish(row,epoch,"ISOLATED",invalid.code(),clock.instant());
            } catch (RuntimeException unavailable) {
                long delay=Math.min(60,1L<<Math.min(6,attempt));
                finish(row,epoch,"PENDING","PUBLISH_UNCONFIRMED",clock.instant().plusMillis(delay*1000+ThreadLocalRandom.current().nextInt(1000)));
            }
        }
        return published;
    }

    private RuntimeMessage message(Map<String,Object> row) {
        String type=text(row,"event_type"), warehouse=text(row,"warehouse_id"), attempt=text(row,"attempt_id");
        tools.jackson.databind.JsonNode body;
        if (FulfillmentService.EVENT_ALLOCATION_COMPLETED.equals(type)) {
            if (!FulfillmentService.NO_WAREHOUSE.equals(warehouse)) throw new MessageRejectedException("INVALID_ALLOCATION_AUDIT_SCOPE");
            body=RuntimeMessage.JSON.readTree(text(row,"payload"));
            if (!attempt.equals(body.path("attemptId").asString())) throw new MessageRejectedException("ALLOCATION_AUDIT_MISMATCH");
        } else {
            if (row.get("delivery_payload")==null) throw new MessageRejectedException("LEGACY_AUTHORIZATION_CONTEXT_MISSING");
            body=RuntimeMessage.JSON.readTree(text(row,"delivery_payload"));
        }
        var message=new RuntimeMessage(1,text(row,"event_id"),"wms-fulfillment",text(row,"enterprise_id"),warehouse,
                type,attempt,1,instant(row.get("created_at")).toString(),null,body);
        if (!FulfillmentService.EVENT_ALLOCATION_COMPLETED.equals(type)) AllocationAuthorizationMessage.from(message);
        return message;
    }
    private boolean finish(Map<String,Object> row,long epoch,String state,String error,Instant next) {
        try (var session=sessions.openSession(false)) {
            int count=session.getMapper(FulfillmentOutboxMapper.class).finish(text(row,"event_id"),epoch,state,error,now(),Timestamp.from(next));
            session.commit();return count==1;
        }
    }
    private Timestamp now() {return Timestamp.from(clock.instant());}
    private static String text(Map<String,Object> row,String field) {
        Object value=row.get(field);
        if (value==null) throw new MessageRejectedException("AUTHORIZATION_FACT_MISSING");return value.toString();
    }
    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return com.lrj.wms.runtime.db.DatabaseInstants.require(local);
        throw new MessageRejectedException("AUTHORIZATION_TIME_MISSING");
    }
}
