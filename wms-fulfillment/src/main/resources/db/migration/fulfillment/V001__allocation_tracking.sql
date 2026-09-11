-- S4-01 履约 attempt/XID/participant 映射。TC 状态仅为观察副本，无自研 decision 权威字段。

CREATE TABLE fulfillment_order (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  source_system VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '来源系统稳定编码',
  source_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源单号',
  request_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化请求SHA-256十六进制',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '履约单状态稳定编码',
  strategy_version BIGINT NOT NULL DEFAULT 0 COMMENT '选仓策略版本',
  active_attempt_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前活动attempt，无活动可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fulfillment_order_source (enterprise_id, source_system, source_order_no),
  CONSTRAINT ck_fulfillment_order_version CHECK (strategy_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局履约单头，fulfillment 拥有';

CREATE TABLE fulfillment_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  fulfillment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属履约单',
  source_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源行号',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  requested_qty DECIMAL(20,6) NOT NULL COMMENT '请求数量',
  base_unit VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '基本单位',
  min_remaining_days INT NOT NULL DEFAULT 0 COMMENT '最小剩余效期天数',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fulfillment_line_source (enterprise_id, fulfillment_id, source_line_id),
  KEY idx_fulfillment_line_order (enterprise_id, fulfillment_id),
  CONSTRAINT ck_fulfillment_line_qty CHECK (requested_qty > 0 AND min_remaining_days >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约行，不存仓内余额';

CREATE TABLE allocation_attempt (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分配尝试标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  fulfillment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属履约单',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'attempt业务状态稳定编码',
  deadline DATETIME(6) NOT NULL COMMENT '尝试截止UTC时刻',
  xid VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '绑定的TC全局XID，未绑定可空',
  tc_observed_status VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '观察到的TC状态副本，非本地全局决定',
  tc_terminal_evidence JSON NULL COMMENT 'TC终态证据副本，缺证据不可放行',
  participant_set_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '固定参与者集合SHA-256十六进制',
  cancel_requested TINYINT NOT NULL DEFAULT 0 COMMENT '是否已请求取消，1是0否',
  launch_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '启动代际',
  launch_owner VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前启动执行器，未领取可空',
  launch_lease_until DATETIME(6) NULL COMMENT '启动租约截止，未领取可空',
  xid_bound_at DATETIME(6) NULL COMMENT 'XID绑定UTC时刻，未绑定可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_allocation_attempt_xid (enterprise_id, xid),
  KEY idx_allocation_attempt_order (enterprise_id, fulfillment_id),
  CONSTRAINT ck_allocation_attempt_epoch CHECK (launch_epoch >= 0 AND version >= 0 AND cancel_requested IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨仓分配尝试，XID绑定一次且TC状态仅为观察副本';

CREATE TABLE allocation_participant (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '参与仓记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属attempt',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '参与仓库',
  reservation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '仓级预占标识，未Try可空',
  xid VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '该仓Try绑定的XID观察，未Try可空',
  branch_id BIGINT NULL COMMENT '该仓Seata分支标识，未Try可空',
  action_name VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'TCC动作名，未Try可空',
  route_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '路由代际观察，不驱动二阶段',
  observed_branch_state VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '仓级分支观察状态，非TC决定',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '仓级观察状态稳定编码',
  last_error VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '最近错误码，无错误可空',
  next_retry_at DATETIME(6) NULL COMMENT '下次重试UTC时刻，无需可空',
  confirmed_version BIGINT NULL COMMENT '确认版本，未确认可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_allocation_participant_wh (enterprise_id, attempt_id, warehouse_id),
  CONSTRAINT ck_allocation_participant_ver CHECK (version >= 0 AND route_epoch >= 0 AND (confirmed_version IS NULL OR confirmed_version >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='attempt固定参与仓，行明细在 participant_line';

CREATE TABLE participant_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '参与仓行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  participant_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属参与仓',
  order_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约行',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  qty DECIMAL(20,6) NOT NULL COMMENT '本仓分配数量',
  base_unit VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '基本单位',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_participant_line (enterprise_id, participant_id, order_line_id),
  CONSTRAINT ck_participant_line_qty CHECK (qty > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='参与仓行数量，Try前固定';

CREATE TABLE allocation_launch (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '启动审计标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属attempt',
  launch_epoch BIGINT NOT NULL COMMENT '启动代际',
  executor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '领取启动权的执行器',
  xid VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '本代际拟绑定XID，空启动可空',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '启动记录状态稳定编码',
  cleanup_state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '空启动清理状态稳定编码',
  error_code VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '启动错误码，无错误可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_allocation_launch_epoch (enterprise_id, attempt_id, launch_epoch),
  CONSTRAINT ck_allocation_launch_epoch CHECK (launch_epoch >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='空启动与XID绑定审计，不是第二全局决策表';
