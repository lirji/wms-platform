-- S6-03 盘点计划、范围、快照行与点数。序列号观察集合留给 S6-03a。

CREATE TABLE count_plan (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '盘点计划标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '盘点状态稳定编码',
  reason_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '盘点原因稳定编码',
  scope_version BIGINT NOT NULL DEFAULT 1 COMMENT '范围版本',
  approved_by VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '审批人，未审批可空',
  approved_at DATETIME(6) NULL COMMENT '审批时刻，未审批可空',
  approval_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '审批身份，未审批可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_plan (enterprise_id, warehouse_id, id),
  CONSTRAINT ck_count_plan_version CHECK (scope_version >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓内盘点计划，门禁范围由 count_scope 维护';

CREATE TABLE count_scope (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '盘点范围行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  count_plan_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属盘点计划',
  location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '冻结库位',
  gate_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '进入范围时的门禁代际',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_scope (enterprise_id, warehouse_id, count_plan_id, location_id),
  KEY idx_count_scope_location (enterprise_id, warehouse_id, location_id, count_plan_id),
  CONSTRAINT ck_count_scope_epoch CHECK (gate_epoch >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点库位范围，同库位有效冻结由门禁仲裁';

CREATE TABLE count_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '盘点快照行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  count_plan_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属盘点计划',
  balance_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '快照库存桶',
  location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '快照库位',
  snapshot_version BIGINT NOT NULL COMMENT '冻结时余额版本',
  snapshot_qty DECIMAL(20,6) NOT NULL COMMENT '冻结时实物量',
  reserved_qty DECIMAL(20,6) NOT NULL COMMENT '冻结时预占量',
  counted_qty DECIMAL(20,6) NULL COMMENT '最近点数，未点可空',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '快照行状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_line (enterprise_id, warehouse_id, count_plan_id, balance_id),
  KEY idx_count_line_plan (enterprise_id, warehouse_id, count_plan_id, status),
  CONSTRAINT ck_count_line_qty CHECK (snapshot_qty >= 0 AND reserved_qty >= 0 AND snapshot_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点冻结快照，扫描不得覆盖原快照';

CREATE TABLE count_observation (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '点数观察标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  count_plan_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属盘点计划',
  count_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属快照行',
  observation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '客户端观察身份',
  qty DECIMAL(20,6) NOT NULL COMMENT '本轮点数',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '点数人',
  round_no INT NOT NULL COMMENT '点数轮次，从1起',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_observation (enterprise_id, warehouse_id, observation_id),
  KEY idx_count_observation_line (enterprise_id, warehouse_id, count_line_id, round_no),
  CONSTRAINT ck_count_observation_qty CHECK (qty >= 0 AND round_no >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点点数/复盘，原扫描不可覆盖';
