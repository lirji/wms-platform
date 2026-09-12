CREATE TABLE serial_recovery_audit (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '重试审计标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '操作者幂等命令',
  intent_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原始登记恢复意图',
  actor_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'JWT审计主体',
  reason VARCHAR(500) NOT NULL COMMENT '人工核查后重试依据',
  expected_epoch BIGINT NOT NULL COMMENT '操作者核对的隔离代际',
  request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '请求及主体摘要，拒绝换参重放',
  created_at DATETIME(6) NOT NULL COMMENT '审计创建UTC时刻',
  PRIMARY KEY(id),
  UNIQUE KEY uk_serial_retry_command(enterprise_id,warehouse_id,command_id),
  KEY idx_serial_retry_intent(enterprise_id,warehouse_id,intent_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登记隔离任务受审计重排，不修改原业务事实';
ALTER TABLE serial_recovery_intent ADD KEY idx_serial_recovery_list(enterprise_id,warehouse_id,created_at,id);
