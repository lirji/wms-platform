-- S7-03 查询投影。可重建；写入仍以权威库存库校验。不发明 OQ-03。

CREATE TABLE projection_inbox (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '消费记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  consumer_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '投影消费者名',
  event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源事件标识',
  aggregate_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源聚合标识',
  aggregate_version BIGINT NOT NULL COMMENT '源聚合版本',
  event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件类型',
  payload VARCHAR(2000) COLLATE utf8mb4_bin NOT NULL COMMENT '事件载荷',
  occurred_at DATETIME(6) NOT NULL COMMENT '源事件发生时刻',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_projection_inbox (enterprise_id, warehouse_id, consumer_name, event_id),
  KEY idx_projection_inbox_agg (enterprise_id, warehouse_id, consumer_name, aggregate_id, aggregate_version),
  CONSTRAINT ck_projection_inbox CHECK (aggregate_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投影 inbox，与视图更新同事务去重';

CREATE TABLE inventory_view (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源余额标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  generation BIGINT NOT NULL COMMENT '投影世代，切换后只读当前世代',
  owner_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '货主',
  location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库位',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品',
  lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次，无批次用稳定空批次键',
  quality_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '质量状态',
  on_hand_qty DECIMAL(20,6) NOT NULL COMMENT '投影实物量',
  reserved_qty DECIMAL(20,6) NOT NULL COMMENT '投影预占量',
  free_execution_claim_qty DECIMAL(20,6) NOT NULL COMMENT '投影执行占用',
  source_version BIGINT NOT NULL COMMENT '已应用的源聚合版本',
  as_of DATETIME(6) NOT NULL COMMENT '本行投影时刻',
  eligible TINYINT NOT NULL COMMENT '展示资格，1可展示0仅追溯',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id, generation),
  UNIQUE KEY uk_inventory_view (enterprise_id, warehouse_id, id, generation),
  KEY idx_inventory_view_sku (enterprise_id, warehouse_id, generation, sku_id, lot_id),
  CONSTRAINT ck_inventory_view CHECK (generation >= 0 AND source_version >= 0 AND eligible IN (0, 1) AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存查询投影，不承担库存判断';

CREATE TABLE projection_checkpoint (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '检查点标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  projection_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '投影名',
  live_generation BIGINT NOT NULL COMMENT '当前对外世代',
  rebuild_generation BIGINT NULL COMMENT '在建世代，无重建可空',
  last_event_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '最近事件，空投影可空',
  last_event_time DATETIME(6) NULL COMMENT '最近事件时刻，空投影可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_projection_checkpoint (enterprise_id, warehouse_id, projection_name),
  CONSTRAINT ck_projection_checkpoint CHECK (live_generation >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='投影世代与位点，重建追平后才切换';
