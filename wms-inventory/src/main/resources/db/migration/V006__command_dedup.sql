-- S2-04 命令幂等与业务同事务。Outbox 领取/发布不改 V005 表结构。

CREATE TABLE command_dedup (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '命令幂等行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源系统稳定编码',
  action VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '动作稳定编码',
  client_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调用方一次操作身份，与Idempotency-Key一致',
  operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端库存操作身份',
  request_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化请求SHA-256十六进制',
  digest_version INT NOT NULL COMMENT '摘要规范版本',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '受理状态稳定编码',
  response_json JSON NULL COMMENT '已受理响应快照，尚未写出可空',
  retain_until DATETIME(6) NOT NULL COMMENT '保留截止UTC时刻',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_command_dedup_client
    (enterprise_id, warehouse_id, source_system, action, client_operation_id),
  KEY idx_command_dedup_operation (enterprise_id, warehouse_id, operation_id),
  CONSTRAINT ck_command_dedup_version CHECK (version >= 0 AND digest_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存命令幂等，与余额流水Outbox同物理事务';
