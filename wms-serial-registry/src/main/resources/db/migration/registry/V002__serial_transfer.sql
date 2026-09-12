-- S6-02 跨仓转移审计。与 serial_registry 同片；UQ(serial, transfer)。

ALTER TABLE serial_registry
  ADD COLUMN transfer_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前或最近转移',
  ADD COLUMN receipt_operation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前目的接收操作';

CREATE TABLE serial_transfer (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '转移审计标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  normalized_serial VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化序列号',
  serial_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '登记身份标识',
  transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调拨或转移业务标识',
  source_warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源仓',
  target_warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '目的仓',
  from_epoch BIGINT NOT NULL COMMENT '源仓归属代际',
  to_epoch BIGINT NULL COMMENT '目的确认后的新代际，未完成可空',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '转移审计状态稳定编码',
  source_release_ref VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '源仓释放事实，未释放可空',
  target_receipt_ref VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '目的接收事实，未接收可空',
  prepare_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '准备转移操作',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_serial_transfer (enterprise_id, sku_id, normalized_serial, transfer_id),
  KEY idx_serial_transfer_serial (enterprise_id, sku_id, normalized_serial, state),
  CONSTRAINT ck_serial_transfer_epoch CHECK (
    from_epoch >= 0 AND (to_epoch IS NULL OR to_epoch > from_epoch) AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='序列号跨仓转移审计，与登记身份同片';
