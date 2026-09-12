-- 库存确认在原分支身份匹配后保存分配业务键；旧观察记录不猜测补齐。
ALTER TABLE allocation_participant ADD COLUMN confirmed_allocation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '可靠库存确认携带的原分配业务标识';
-- 显式投递预算为后续可靠发布及人工恢复准备，旧写入保持默认PENDING兼容。
ALTER TABLE fulfillment_outbox
 ADD COLUMN claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '发布领取代际，防旧发布器回写',
 ADD COLUMN retry_base_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '授权重排的预算基线，代际不重置',
 ADD COLUMN lease_until DATETIME(6) NULL COMMENT 'UTC发布领取租约，到期不代表业务撤销',
 ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC下次允许发布时刻',
 ADD COLUMN error_code VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '稳定脱敏投递错误码',
 ADD CONSTRAINT ck_fulfillment_outbox_claim CHECK(claim_epoch >= retry_base_epoch AND retry_base_epoch >= 0);
CREATE INDEX idx_fulfillment_outbox_due ON fulfillment_outbox(status,next_attempt_at,event_id);
CREATE INDEX idx_fulfillment_outbox_age ON fulfillment_outbox(status,created_at);
CREATE INDEX idx_fulfillment_outbox_recovery ON fulfillment_outbox(enterprise_id,warehouse_id,status,event_id);
