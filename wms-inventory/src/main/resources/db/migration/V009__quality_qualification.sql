-- S3-01 库存质量资格。不写入库单，按 inspection 版本防乱序。

CREATE TABLE quality_qualification (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '质量资格标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  inspection_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源质检单',
  source_version BIGINT NOT NULL COMMENT '已接受的质检版本',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU',
  lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次，无批次用固定非空标识',
  result_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '生效结论稳定编码',
  effective_state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '资格生效状态稳定编码',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '写入该资格的库存命令',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quality_qualification_inspection (enterprise_id, warehouse_id, inspection_id),
  CONSTRAINT ck_quality_qualification_version CHECK (source_version >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存质量资格，按质检版本接收不覆盖更新的乱序事件';
