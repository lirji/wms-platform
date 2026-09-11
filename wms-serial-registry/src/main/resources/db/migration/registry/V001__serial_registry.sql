-- S3-02 全局序列号身份。唯一范围 enterprise+SKU+normalized_serial。

CREATE TABLE serial_registry (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '登记身份标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  normalized_serial VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化序列号',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '登记状态稳定编码',
  owner_warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前归属仓，未归属可空',
  owner_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '归属代际',
  claim_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '首次认领操作',
  route_bucket INT NOT NULL COMMENT '逻辑分片桶，由身份哈希得到',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_serial_registry_identity (enterprise_id, sku_id, normalized_serial),
  KEY idx_serial_registry_bucket (enterprise_id, route_bucket, sku_id),
  CONSTRAINT ck_serial_registry_epoch CHECK (owner_epoch >= 0 AND route_bucket >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='序列号全局身份，按企业+SKU+规范化序列号唯一';
