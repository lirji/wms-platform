-- S1-06 库存侧效果身份与尝试；S2 再补 command/posting。不靠哈希碰撞证明业务唯一。

CREATE TABLE stock_effect (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '不透明effectId，首次登记分配',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓库及路由键',
  source_service VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源服务稳定编码',
  action VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '动作稳定编码',
  fact_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '事实类型稳定编码',
  fact_parent_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '父事实标识，不适用时仍非空',
  fact_part_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分批/子动作标识，不适用时仍非空',
  fact_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '行事实标识，不适用时仍非空',
  business_effect_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '与effectId相同的不透明业务效果键',
  active_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前活动命令，未授权可空',
  applied_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '唯一有效过账命令，未过账可空',
  attempt_no BIGINT NOT NULL COMMENT '已发放尝试序号，0表示尚无尝试',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '效果状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_effect_fact (enterprise_id, warehouse_id, source_service, action, fact_type, fact_parent_id, fact_part_id, fact_line_id),
  UNIQUE KEY uk_stock_effect_key (enterprise_id, warehouse_id, source_service, action, business_effect_key),
  KEY idx_stock_effect_warehouse (enterprise_id, warehouse_id, id),
  CONSTRAINT ck_stock_effect_attempt CHECK (attempt_no >= 0),
  CONSTRAINT ck_stock_effect_version CHECK (version >= 0),
  CONSTRAINT ck_stock_effect_id_key CHECK (id = business_effect_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存效果身份，由权威事实唯一映射不透明effectId';

CREATE TABLE stock_effect_attempt (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '不透明executionAttemptId',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓库及路由键',
  effect_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属效果',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本尝试命令占位',
  previous_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '安全关闭所依据的上一命令，首次可空',
  attempt_no BIGINT NOT NULL COMMENT '效果内尝试序号，从1起',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '尝试状态稳定编码',
  digest_version BIGINT NOT NULL COMMENT '意图摘要规范版本',
  intent_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化意图SHA-256十六进制',
  canonical_request VARCHAR(1024) NOT NULL COMMENT '按digestVersion保存的原规范化请求',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_effect_attempt_no (enterprise_id, warehouse_id, effect_id, attempt_no),
  UNIQUE KEY uk_stock_effect_attempt_command (enterprise_id, warehouse_id, command_id),
  KEY idx_stock_effect_attempt_effect (enterprise_id, warehouse_id, effect_id, attempt_no),
  CONSTRAINT ck_stock_effect_attempt_no CHECK (attempt_no >= 1),
  CONSTRAINT ck_stock_effect_attempt_digest CHECK (digest_version >= 1),
  CONSTRAINT ck_stock_effect_attempt_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='效果执行尝试，同尝试意图不可改，安全关闭后才可下一号';

CREATE TABLE write_idempotency (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '幂等记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓库及路由键',
  client_operation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '与Idempotency-Key一致的调用键',
  request_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化请求SHA-256',
  resource_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '首次成功写入的资源标识',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_write_idempotency (enterprise_id, warehouse_id, client_operation_id),
  CONSTRAINT ck_write_idempotency_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓内写调用幂等，同键异内容拒绝';
