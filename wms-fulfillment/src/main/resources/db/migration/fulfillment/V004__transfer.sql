-- S6-01 调拨总单、仓级子单、在途行与按操作键去重的发出/接收事实。额度 token 留给 S6-01a。

CREATE TABLE transfer_order (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调拨总单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  source_warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源仓库',
  target_warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '目的仓库',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '调拨状态稳定编码',
  source_document_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '源仓作业单据，未建可空',
  target_document_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '目的仓作业单据，未建可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_order_enterprise (enterprise_id, id),
  CONSTRAINT ck_transfer_order_wh CHECK (source_warehouse_id <> target_warehouse_id AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局调拨总单，fulfillment 拥有';

CREATE TABLE transfer_leg (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓级子单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调拨总单',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '子单仓库',
  role VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'SOURCE或TARGET',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '子单状态稳定编码',
  document_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '仓内作业单，未建可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_leg (enterprise_id, transfer_id, warehouse_id),
  CONSTRAINT ck_transfer_leg_role CHECK (role IN ('SOURCE','TARGET') AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨仓级子单';

CREATE TABLE transfer_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调拨行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调拨总单',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  business_lot_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '业务批次键，无批次用NO_LOT',
  source_lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源仓lot标识，无批次用NO_LOT',
  target_lot_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '目的仓lot映射，未映射可空',
  planned_qty DECIMAL(20,6) NOT NULL COMMENT '计划数量',
  issued_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已发出数量',
  received_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已接收数量',
  loss_confirmed_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '已确认损耗',
  active_receipt_quota DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '未确认接收额度，S6-01a占用',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_line (enterprise_id, transfer_id, id),
  KEY idx_transfer_line_order (enterprise_id, transfer_id),
  CONSTRAINT ck_transfer_line_qty CHECK (
    planned_qty > 0 AND issued_qty >= 0 AND received_qty >= 0 AND loss_confirmed_qty >= 0
    AND active_receipt_quota >= 0 AND issued_qty <= planned_qty
    AND received_qty + loss_confirmed_qty + active_receipt_quota <= issued_qty AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨在途行，接收+损耗+额度不超过已发出';

CREATE TABLE transfer_fact (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调拨事实标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调拨总单',
  line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调拨行',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事实发生仓',
  action VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'ISSUE或RECEIVE',
  operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端操作键',
  quantity DECIMAL(20,6) NOT NULL COMMENT '事实数量',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_fact_operation (enterprise_id, warehouse_id, action, operation_id),
  KEY idx_transfer_fact_line (enterprise_id, transfer_id, line_id),
  CONSTRAINT ck_transfer_fact_qty CHECK (quantity > 0 AND action IN ('ISSUE','RECEIVE') AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨发出/接收事实，按仓+动作+操作键去重';
