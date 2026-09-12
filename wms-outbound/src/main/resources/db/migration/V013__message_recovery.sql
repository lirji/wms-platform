-- 人工重试仅重建预算基线，领取代际永不回退；原事件内容和身份保持不变。
ALTER TABLE runtime_message_inbox ADD COLUMN retry_base_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '最近获授权重试的代际基线，不改变claim_epoch';
ALTER TABLE source_outbox ADD COLUMN retry_base_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '最近获授权重试的代际基线，不改变claim_epoch';
CREATE TABLE message_recovery_audit (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '重试审计技术标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'JWT企业范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'JWT获授权仓库范围',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '人工重试幂等请求键',
  queue_kind VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '本库Inbox或Outbox的封闭类别',
  message_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '被重试的原消息标识',
  request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '请求含操作者原因的规范化摘要',
  payload_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '重试时原消息正文摘要，用于审计',
  previous_epoch BIGINT NOT NULL COMMENT '授权重试前领取代际',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'JWT真实操作者',
  reason VARCHAR(500) NOT NULL COMMENT '人工核对后重试原因，不填写机密',
  created_at DATETIME(6) NOT NULL COMMENT '授权重试时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_recovery_command (enterprise_id, warehouse_id, command_id),
  KEY idx_message_recovery_target (enterprise_id, warehouse_id, queue_kind, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隔离消息人工重试审计，与队列状态调整同事务';

-- 运维列表先按权限范围与状态过滤，再用原事件身份游标翻页。
CREATE INDEX idx_message_recovery_page ON source_outbox (enterprise_id, warehouse_id, status, event_id);
