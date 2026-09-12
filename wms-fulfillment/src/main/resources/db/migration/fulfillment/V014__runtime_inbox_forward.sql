-- 修复生产迁移路径只扫描子目录造成的Inbox缺表，保留父目录已提交迁移的校验和。
-- 已手工准备过的表保留，追加旧Inbox预算基线与积压索引。
-- 每个服务持有自己的Inbox；消息已持久化后才能提交Kafka位点。
CREATE TABLE IF NOT EXISTS runtime_message_inbox (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本库接收记录标识',
  event_key CHAR(64) COLLATE utf8mb4_bin NULL COMMENT '来源与企业仓事件身份摘要，无法解析的隔离消息可空',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '验证后的企业范围，非法消息可空',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '验证后的仓范围，非法消息可空',
  source_service VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '与受信Topic匹配的来源服务',
  topic_name VARCHAR(190) COLLATE utf8mb4_bin NOT NULL COMMENT 'Kafka来源Topic',
  partition_no INT NOT NULL COMMENT 'Kafka分区',
  offset_no BIGINT NOT NULL COMMENT '原始消费位点',
  payload_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '内容摘要，用于拒绝事件身份复用',
  payload MEDIUMTEXT NOT NULL COMMENT '有界原始信封，非法消息保留隔离证据',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'PENDING/CLAIMED/DONE/ISOLATED',
  error_code VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '脱敏的失败类别',
  claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '领取代际，拒绝旧执行器提交',
  lease_until DATETIME(6) NULL COMMENT 'UTC领取期限，不代表撤销业务效果',
  next_attempt_at DATETIME(6) NOT NULL COMMENT 'UTC下次重试时刻',
  retry_base_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '人工重排预算基线，领取代际永不回退',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC接收时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC更新时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_runtime_event (event_key),
  UNIQUE KEY uk_runtime_kafka_offset (topic_name, partition_no, offset_no),
  KEY idx_runtime_inbox_due (status, next_attempt_at, id),
  KEY idx_runtime_inbox_age (status, created_at),
  KEY idx_runtime_inbox_scope (enterprise_id, warehouse_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本服务可靠消息接收与处理队列';

CREATE TABLE IF NOT EXISTS message_recovery_audit (
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

SET @wms_inbox_column_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='runtime_message_inbox' AND column_name='retry_base_epoch'),
 'SELECT 1', 'ALTER TABLE runtime_message_inbox ADD COLUMN retry_base_epoch BIGINT NOT NULL DEFAULT 0 COMMENT ''人工重排预算基线，领取代际永不回退''');
PREPARE wms_inbox_column_stmt FROM @wms_inbox_column_ddl;
EXECUTE wms_inbox_column_stmt;
DEALLOCATE PREPARE wms_inbox_column_stmt;
SET @wms_inbox_index_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='runtime_message_inbox' AND index_name='idx_runtime_inbox_age'),
 'SELECT 1', 'CREATE INDEX idx_runtime_inbox_age ON runtime_message_inbox(status,created_at)');
PREPARE wms_inbox_index_stmt FROM @wms_inbox_index_ddl;
EXECUTE wms_inbox_index_stmt;
DEALLOCATE PREPARE wms_inbox_index_stmt;
