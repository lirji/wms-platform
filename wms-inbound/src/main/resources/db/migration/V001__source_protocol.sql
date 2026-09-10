-- S2-04a 来源命令/实物/收件箱。入库单在 S3-01 另迁，本文件占用 V001。

CREATE TABLE source_effect (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源效果不透明标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  source_service VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源服务稳定编码',
  action VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '动作稳定编码',
  fact_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '事实类型稳定编码',
  fact_parent_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '父事实标识',
  fact_part_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分批事实标识',
  fact_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '行事实标识',
  business_effect_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '与id相同的业务效果键',
  active_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前活动命令，未受理可空',
  applied_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已同步的库存命令，未回执可空',
  attempt_no BIGINT NOT NULL COMMENT '已发放尝试序号',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '来源效果状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_source_effect_fact
    (enterprise_id, warehouse_id, source_service, action, fact_type, fact_parent_id, fact_part_id, fact_line_id),
  UNIQUE KEY uk_source_effect_key (enterprise_id, warehouse_id, source_service, action, business_effect_key),
  CONSTRAINT ck_source_effect_id CHECK (id = business_effect_key),
  CONSTRAINT ck_source_effect_attempt CHECK (attempt_no >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源效果身份，不写入库存库';

CREATE TABLE source_command (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '与command_id相同',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源命令身份',
  source_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源本地操作身份',
  source_execution_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已绑定实物事实，未执行可空',
  business_effect_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源效果键',
  action VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '动作稳定编码',
  execution_attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本命令执行尝试',
  attempt_no BIGINT NOT NULL COMMENT '效果内尝试序号',
  payload_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '冻结请求SHA-256',
  digest_version INT NOT NULL COMMENT '摘要规范版本',
  payload_json JSON NOT NULL COMMENT '冻结请求正文',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '来源命令状态稳定编码',
  inventory_operation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '库存操作身份，未回执可空',
  posting_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '库存凭证，未回执可空',
  retry_at DATETIME(6) NULL COMMENT '下次恢复UTC时刻，不需重试可空',
  compensates_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '补偿原命令，非补偿可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_source_command_id (enterprise_id, warehouse_id, command_id),
  UNIQUE KEY uk_source_command_attempt (enterprise_id, warehouse_id, business_effect_key, action, attempt_no),
  CONSTRAINT ck_source_command_id CHECK (id = command_id),
  CONSTRAINT ck_source_command_attempt CHECK (attempt_no >= 1 AND digest_version >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源命令，T1与Outbox同事务';

CREATE TABLE source_execution (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源实物事实标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '对应来源命令',
  action VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '动作稳定编码',
  physical_status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '实物状态稳定编码',
  stock_sync_status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库存同步状态稳定编码',
  physical_qty DECIMAL(20,6) NOT NULL COMMENT '已确认实物量',
  posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已过账回执量',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '实物操作人',
  executed_at DATETIME(6) NOT NULL COMMENT '实物发生UTC时刻',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_source_execution_op (enterprise_id, warehouse_id, command_id, action),
  CONSTRAINT ck_source_execution_qty CHECK (physical_qty >= 0 AND posted_qty >= 0 AND posted_qty <= physical_qty AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源实物事实，不因任务取消删除';

CREATE TABLE source_inbox (
  event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '已消费库存事件标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '回执对应命令',
  event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件类型稳定编码',
  payload JSON NOT NULL COMMENT '回执正文',
  consumed_at DATETIME(6) NOT NULL COMMENT 'T3消费UTC时刻',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_source_inbox_command (enterprise_id, warehouse_id, command_id, event_type),
  CONSTRAINT ck_source_inbox_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源收件箱，与单据累计同事务';

CREATE TABLE source_outbox (
  event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '待投递来源事件',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '产生该事件的命令',
  event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件类型稳定编码',
  payload JSON NOT NULL COMMENT '版本化事件正文',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '投递状态稳定编码',
  next_attempt_at DATETIME(6) NOT NULL COMMENT '下次可领取UTC时刻',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (event_id),
  KEY idx_source_outbox_publish (status, next_attempt_at, event_id),
  CONSTRAINT ck_source_outbox_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源Outbox，与T1命令同物理事务';
