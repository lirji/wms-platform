CREATE TABLE serial_http_command (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '内部命令审计技术标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '签名主体获授权的企业',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本命令发起仓',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'HTTP幂等命令键，跨动作不复用',
  request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '动作参数和服务主体的规范化摘要',
  action VARCHAR(32) NOT NULL COMMENT '登记动作稳定编码',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '经过验签的调用服务主体',
  result JSON NULL COMMENT '与登记状态同事务完成的原始结果，不代表未来实时状态',
  created_at DATETIME(6) NOT NULL COMMENT '内部命令受理时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_serial_http_command (enterprise_id, command_id),
  KEY idx_serial_http_scope (enterprise_id, warehouse_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='序列号登记内部命令幂等与服务主体审计';
