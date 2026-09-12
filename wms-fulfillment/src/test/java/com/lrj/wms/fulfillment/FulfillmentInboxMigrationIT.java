package com.lrj.wms.fulfillment;

import com.lrj.wms.runtime.db.*;
import com.lrj.wms.runtime.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.mysql.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 历史生产子目录缺Inbox，以追加迁移补齐；旧人工准备的Inbox原内容和代际不得丢失。 */
class FulfillmentInboxMigrationIT {
    @Test
    void forwardMigrationPreservesOldInboxAndEnablesAuditedRecoveryAndMetrics() {
        try(var mysql=new MySQLContainer("mysql:8.4.11").withDatabaseName("fulfillment").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            var source=new com.mysql.cj.jdbc.MysqlDataSource(); source.setUrl(RuntimeDataSources.withTimeZone(mysql.getJdbcUrl(),"UTC"));
            source.setUser(mysql.getUsername()); source.setPassword(mysql.getPassword());
            var old=Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").target("013").load();
            new DatabaseTimePolicy("UTC","").initialize(source,old::migrate);
            // 模拟旧库单独准备过V009表，但未获得V010/V011的索引和重排预算字段。
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V009__runtime_message_inbox.sql")).execute(source);
            var db=new JdbcTemplate(source);
            var payload=RuntimeMessage.JSON.valueToTree(Map.of("state","CONFIRMED","note","原始中文"));
            var message=new RuntimeMessage(1,"ORIGINAL","wms-inventory","ENT","WH","ReservationConfirmed","RES",1,
                    "2026-09-12T00:00:00.123456Z","original",payload);
            var at=Timestamp.from(Instant.parse(message.occurredAt()));
            db.update("INSERT INTO runtime_message_inbox(id,event_key,enterprise_id,warehouse_id,source_service,topic_name,partition_no,offset_no,payload_hash,payload,status,error_code,claim_epoch,next_attempt_at,created_at,updated_at) VALUES ('OLD',?,'ENT','WH','wms-inventory','wms.forward.fulfillment.results',0,1,?,?,'ISOLATED','PROCESSING_FAILED',7,?,?,?)",
                    message.identity(),RuntimeMessage.contentHash(message.encode()),message.encode(),at,at,at);
            var current=Flyway.configure().dataSource(source).locations("classpath:db/migration/fulfillment").load();
            current.migrate(); current.validate();
            assertEquals(message.encode(),db.queryForObject("SELECT payload FROM runtime_message_inbox WHERE id='OLD'",String.class));
            assertEquals(7L,db.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE id='OLD'",Long.class));
            assertEquals(at.toInstant(),db.queryForObject("SELECT created_at FROM runtime_message_inbox WHERE id='OLD'",Timestamp.class).toInstant());
            assertEquals(1,db.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='runtime_message_inbox' AND index_name='idx_runtime_inbox_age'",Integer.class));
            var sessions=new FulfillmentPersistence().sqlSessionFactory(source,current,new DatabaseBudget(4,0,1000,500,5,1000,10000));
            var inbox=new RuntimeInbox(sessions,Map.of("wms.forward.fulfillment.results","wms-inventory"),Clock.systemUTC());
            var recovery=new MessageRecoveryService(sessions,MessageQueueMetrics.Queue.FULFILLMENT_OUTBOX,inbox,Clock.systemUTC());
            var result=recovery.retry("ENT","WH","INBOX","OLD","RETRY",7,"旧库升级后核对恢复","operator");
            assertEquals("RETRY_ACCEPTED",result.get("state"));
            assertEquals(Boolean.TRUE,recovery.retry("ENT","WH","INBOX","OLD","RETRY",7,"旧库升级后核对恢复","operator").get("replayed"));
            assertEquals(7L,db.queryForObject("SELECT retry_base_epoch FROM runtime_message_inbox WHERE id='OLD'",Long.class));
            assertEquals(7L,db.queryForObject("SELECT claim_epoch FROM runtime_message_inbox WHERE id='OLD'",Long.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM message_recovery_audit",Integer.class));
            var registry=new SimpleMeterRegistry();
            try {
                new MessageQueueMetrics(sessions,registry,MessageQueueMetrics.Queue.FULFILLMENT_OUTBOX,Clock.systemUTC()).sampleDue();
                assertEquals(1.0,registry.get("wms.messaging.backlog").tag("queue","INBOX").tag("state","PENDING").gauge().value());
                assertEquals(0.0,registry.get("wms.messaging.backlog").tag("queue","FULFILLMENT_OUTBOX").tag("state","PENDING").gauge().value());
            } finally { registry.close(); }
            // 第二次启动没有新增迁移；没有使用repair改写历史或重建表。
            assertEquals(0,current.migrate().migrationsExecuted);
        }
    }
}
