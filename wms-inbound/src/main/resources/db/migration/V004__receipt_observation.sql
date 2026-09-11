-- S3-05 收货分批身份与离线观察映射。不写库存库。

CREATE TABLE inbound_receipt_part (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分批记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  receipt_session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '收货会话',
  inbound_order_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库单',
  inbound_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库行',
  part_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '受控分批身份',
  qty DECIMAL(20,6) NOT NULL COMMENT '本分批数量',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '首次登记的来源命令',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '收货人',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '分批状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_receipt_part_fact (enterprise_id, warehouse_id, receipt_session_id, part_id, inbound_line_id),
  KEY idx_receipt_part_line (enterprise_id, warehouse_id, inbound_line_id),
  CONSTRAINT ck_receipt_part_qty CHECK (qty > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货分批事实，会话+分批+行唯一';

CREATE TABLE device_observation_binding (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '观察绑定标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  device_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '设备身份',
  session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '设备会话',
  sequence_no BIGINT NOT NULL COMMENT '会话内序号',
  business_effect_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '绑定的来源效果',
  receipt_session_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '收货会话',
  part_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分批身份',
  inbound_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库行',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '恢复用命令',
  payload_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '观察摘要SHA-256',
  digest_version INT NOT NULL COMMENT '摘要规范版本',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '绑定状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_observation (enterprise_id, warehouse_id, device_id, session_id, sequence_no),
  KEY idx_observation_effect (enterprise_id, warehouse_id, business_effect_key),
  CONSTRAINT ck_observation_sequence CHECK (sequence_no >= 1 AND digest_version >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='离线设备观察与效果映射，同会话序号不能指向第二效果';
