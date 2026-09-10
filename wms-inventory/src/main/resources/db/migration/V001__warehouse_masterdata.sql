-- inventory 主数据：仓、库位、门禁、SKU、单位版本、批次。OQ-03 未确认前不把单位/效期换算默认值写成生产规则。

CREATE TABLE warehouse (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键，与主键一致',
  code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '仓编码',
  name VARCHAR(512) NOT NULL COMMENT '仓名称',
  timezone VARCHAR(64) NOT NULL COMMENT '合法IANA时区，用于展示与日期换算输入',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '仓状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouse_enterprise_code (enterprise_id, code),
  UNIQUE KEY uk_warehouse_enterprise_warehouse (enterprise_id, warehouse_id),
  CONSTRAINT ck_warehouse_id_match CHECK (id = warehouse_id),
  CONSTRAINT ck_warehouse_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓主数据，由inventory masterdata拥有';

CREATE TABLE location (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库位标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓库及路由键',
  code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库位编码',
  zone_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库区编码',
  location_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库位类型稳定编码',
  capacity_qty DECIMAL(20,6) NULL COMMENT '库位容量，与容量单位成对出现',
  capacity_unit VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '容量单位，与容量数量成对出现',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '库位状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_location_warehouse_code (enterprise_id, warehouse_id, code),
  KEY idx_location_warehouse (enterprise_id, warehouse_id, id),
  CONSTRAINT ck_location_version CHECK (version >= 0),
  CONSTRAINT ck_location_capacity_pair CHECK (
    (capacity_qty IS NULL AND capacity_unit IS NULL)
    OR (capacity_qty IS NOT NULL AND capacity_unit IS NOT NULL AND capacity_qty >= 0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库位主数据，容量单位必须成对且兼容';

CREATE TABLE location_gate (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库位门禁标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓库及路由键',
  location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '被门禁保护的库位',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '门禁状态：OPEN/QUIESCING/FROZEN/MAINTENANCE',
  reason_code VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT '门禁原因稳定编码',
  fence_epoch BIGINT NOT NULL COMMENT '门禁代际，库存写必须核对',
  count_plan_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前冻结所属盘点计划，非冻结可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_gate_location (enterprise_id, warehouse_id, location_id),
  CONSTRAINT ck_gate_epoch CHECK (fence_epoch >= 0),
  CONSTRAINT ck_gate_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库位写门禁，所有库存写事务必须锁定此行';

CREATE TABLE sku (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品编码',
  name VARCHAR(512) NOT NULL COMMENT '商品名称',
  base_unit VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '基础单位稳定编码',
  quantity_scale INT NOT NULL COMMENT '基础单位允许小数位，0到6',
  lot_enabled TINYINT NOT NULL COMMENT '是否启用批次，1启用0关闭',
  serial_enabled TINYINT NOT NULL COMMENT '是否启用序列号，1启用0关闭',
  expiry_enabled TINYINT NOT NULL COMMENT '是否启用效期，1启用0关闭',
  policy_version BIGINT NOT NULL COMMENT '单位与策略版本',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '商品状态稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_enterprise_code (enterprise_id, code),
  CONSTRAINT ck_sku_scale CHECK (quantity_scale BETWEEN 0 AND 6),
  CONSTRAINT ck_sku_flags CHECK (
    lot_enabled IN (0, 1) AND serial_enabled IN (0, 1) AND expiry_enabled IN (0, 1)
  ),
  CONSTRAINT ck_sku_serial_integer CHECK (serial_enabled = 0 OR quantity_scale = 0),
  CONSTRAINT ck_sku_policy_version CHECK (policy_version >= 0),
  CONSTRAINT ck_sku_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主数据，序列号商品基础单位必须为整数';

CREATE TABLE sku_unit (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品单位版本标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属商品',
  unit_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '单位编码',
  numerator DECIMAL(20,0) NOT NULL COMMENT '换算分子，正整数',
  denominator DECIMAL(20,0) NOT NULL COMMENT '换算分母，正整数',
  policy_version BIGINT NOT NULL COMMENT '对应商品策略版本',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_sku_unit_version (enterprise_id, sku_id, unit_code, policy_version),
  KEY idx_sku_unit_sku (enterprise_id, sku_id, policy_version),
  CONSTRAINT ck_sku_unit_ratio CHECK (numerator > 0 AND denominator > 0),
  CONSTRAINT ck_sku_unit_policy CHECK (policy_version >= 0),
  CONSTRAINT ck_sku_unit_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品单位正有理数换算，结果必须精确落到基础单位精度';

CREATE TABLE lot (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次标识，无批次库存使用固定非空标识NO_LOT',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓库及路由键',
  owner_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '货权主体，具体主体集合待OQ-03确认',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属商品',
  lot_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源批号',
  business_lot_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '跨仓稳定批次业务键',
  produced_at DATETIME(6) NULL COMMENT '生产时刻UTC，缺省表示源未提供',
  expires_at DATETIME(6) NULL COMMENT '失效时刻UTC，有效区间为左闭右开；不得默认日界00:00',
  source_date VARCHAR(32) NULL COMMENT '源系统原始日期文本，保留换算前输入',
  expiry_rule_version BIGINT NOT NULL COMMENT '日期转UTC规则版本，0表示尚未绑定已确认规则',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lot_business (enterprise_id, warehouse_id, owner_id, sku_id, lot_code),
  KEY idx_lot_fefo (warehouse_id, sku_id, expires_at, id),
  CONSTRAINT ck_lot_expiry_order CHECK (produced_at IS NULL OR expires_at IS NULL OR expires_at > produced_at),
  CONSTRAINT ck_lot_rule_version CHECK (expiry_rule_version >= 0),
  CONSTRAINT ck_lot_version CHECK (version >= 0),
  CONSTRAINT ck_lot_not_sentinel CHECK (id <> 'NO_LOT' AND lot_code <> 'NO_LOT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓级批次主数据；无批次SKU使用sentinel NO_LOT，不在本表插入该标识';
