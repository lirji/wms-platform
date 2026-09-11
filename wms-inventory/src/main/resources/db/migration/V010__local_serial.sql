-- S3-03 仓内序列号接收记录。登记不可用时保留意向，不删除 HOLD 库存。

CREATE TABLE local_serial (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本地序列号记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  serial_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化序列号',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次，无批次用固定非空标识',
  balance_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'HOLD 桶，未入账可空',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '本地登记状态稳定编码',
  owner_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '本地观察到的归属代际',
  receipt_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '收货库存操作',
  registry_state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '最近观察到的登记状态',
  registry_error VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '登记失败码，正常可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_local_serial (enterprise_id, warehouse_id, serial_id),
  KEY idx_local_serial_receipt (enterprise_id, warehouse_id, receipt_operation_id),
  CONSTRAINT ck_local_serial_epoch CHECK (owner_epoch >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓内序列号接收与放行，登记权威在独立登记库';
