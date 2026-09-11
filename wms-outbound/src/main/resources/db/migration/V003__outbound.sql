-- S5-01 出库单/行、拣发任务与包裹。V001 已被来源协议占用。不写库存库。

CREATE TABLE outbound_order (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  allocation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约分配标识',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分配尝试标识',
  owner_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '货主',
  execution_authorization_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '进入PICKING前必须有效',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '出库单状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_order_attempt (enterprise_id, warehouse_id, allocation_id, attempt_id),
  CONSTRAINT ck_outbound_order_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓级出库单头，outbound 拥有';

CREATE TABLE outbound_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  order_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属出库单',
  order_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约行号',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  allocated_qty DECIMAL(20,6) NOT NULL COMMENT '分配数量',
  picked_physical_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已确认拣货实物量',
  picked_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已过账拣货量',
  packed_physical_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已确认包装实物量',
  packed_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已过账包装量',
  shipped_physical_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已确认发运实物量',
  shipped_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已过账发运量',
  cancelled_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '发运前取消量',
  base_unit VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '基本单位',
  stock_sync_status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库存同步状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_line (enterprise_id, warehouse_id, order_id, order_line_id),
  KEY idx_outbound_line_order (enterprise_id, warehouse_id, order_id),
  CONSTRAINT ck_outbound_line_qty CHECK (
    allocated_qty > 0 AND cancelled_qty >= 0
    AND picked_physical_qty >= picked_posted_qty AND picked_posted_qty >= 0
    AND packed_physical_qty >= packed_posted_qty AND packed_posted_qty >= 0
    AND shipped_physical_qty >= shipped_posted_qty AND shipped_posted_qty >= 0
    AND packed_physical_qty <= picked_physical_qty
    AND shipped_physical_qty <= packed_physical_qty
    AND picked_physical_qty + cancelled_qty <= allocated_qty
    AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库行，实物与过账双累计';

CREATE TABLE outbound_task (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库任务标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  task_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '任务类型稳定编码',
  document_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库单',
  document_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库行',
  source_location_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '来源库位，未指定可空',
  target_location_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '目标库位，未指定可空',
  planned_qty DECIMAL(20,6) NOT NULL COMMENT '计划数量',
  completed_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已完成实物量',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '任务状态稳定编码',
  assignee_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '领取人，未领取可空',
  claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '领取代际',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  KEY idx_outbound_task_state (enterprise_id, warehouse_id, state, task_type, id),
  CONSTRAINT ck_outbound_task_qty CHECK (
    planned_qty > 0 AND completed_qty >= 0 AND completed_qty <= planned_qty
    AND claim_epoch >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库拣货/回库任务';

CREATE TABLE outbound_package (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '包裹标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  order_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属出库单',
  package_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '包裹号',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '包裹状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_package_no (enterprise_id, warehouse_id, order_id, package_no),
  CONSTRAINT ck_outbound_package_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库包裹头，不持库存余额';

CREATE TABLE package_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '包裹行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  package_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属包裹',
  outbound_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库行',
  qty DECIMAL(20,6) NOT NULL COMMENT '本包裹数量',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_package_line (enterprise_id, warehouse_id, package_id, outbound_line_id),
  CONSTRAINT ck_package_line_qty CHECK (qty > 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='包裹行数量，发运前可改未执行包裹';
