-- S3-01 入库单/行、质检与上架任务。V001 已被来源协议占用。不写库存库。

CREATE TABLE inbound_order (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  external_source VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '外部来源稳定编码',
  external_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源单号',
  owner_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '货主',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '入库单状态稳定编码',
  expected_at DATETIME(6) NULL COMMENT '预期到货UTC时刻，未知可空',
  source_version BIGINT NOT NULL DEFAULT 0 COMMENT '来源版本',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inbound_order_external (enterprise_id, warehouse_id, external_source, external_no),
  CONSTRAINT ck_inbound_order_version CHECK (source_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单头，inbound 拥有';

CREATE TABLE inbound_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  order_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属入库单',
  external_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源行号',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  expected_qty DECIMAL(20,6) NOT NULL COMMENT '预期数量',
  received_physical_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已确认收货实物量',
  received_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已过账收货量',
  putaway_physical_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已确认上架实物量',
  putaway_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已过账上架量',
  closed_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已关闭未执行量',
  base_unit VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '基本单位',
  stock_sync_status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库存同步状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_inbound_line_external (enterprise_id, warehouse_id, order_id, external_line_id),
  KEY idx_inbound_line_order (enterprise_id, warehouse_id, order_id),
  CONSTRAINT ck_inbound_line_qty CHECK (
    expected_qty >= 0 AND closed_qty >= 0
    AND received_physical_qty >= received_posted_qty AND received_posted_qty >= 0
    AND putaway_physical_qty >= putaway_posted_qty AND putaway_posted_qty >= 0
    AND putaway_physical_qty <= received_physical_qty
    AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库行，实物与过账双累计';

CREATE TABLE quality_inspection (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '质检单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  inbound_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '对应入库行',
  inspected_qty DECIMAL(20,6) NOT NULL COMMENT '送检数量',
  accepted_qty DECIMAL(20,6) NOT NULL COMMENT '合格数量',
  rejected_qty DECIMAL(20,6) NOT NULL COMMENT '不合格数量',
  result_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '质检结论稳定编码',
  source_version BIGINT NOT NULL COMMENT '质检版本，防乱序',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '质检人',
  evidence_refs JSON NULL COMMENT '证据引用，无可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quality_inspection_line_ver (enterprise_id, warehouse_id, inbound_line_id, source_version),
  CONSTRAINT ck_quality_inspection_qty CHECK (
    inspected_qty >= 0 AND accepted_qty >= 0 AND rejected_qty >= 0
    AND accepted_qty + rejected_qty = inspected_qty
    AND source_version >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库质检结论，不写入库存资格表';

CREATE TABLE inbound_task (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库任务标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  task_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '任务类型稳定编码',
  document_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库单',
  document_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '入库行',
  source_location_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '来源库位，收货可空',
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
  KEY idx_inbound_task_state (enterprise_id, warehouse_id, state, task_type, id),
  CONSTRAINT ck_inbound_task_qty CHECK (
    planned_qty >= 0 AND completed_qty >= 0 AND completed_qty <= planned_qty
    AND claim_epoch >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库收货/上架任务';
