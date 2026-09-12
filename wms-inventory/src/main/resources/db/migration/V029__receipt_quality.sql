-- 每批质量的库存权威状态与所有转桶流水同行事务提交。
CREATE TABLE stock_receipt_quality (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '技术主键',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库存所属仓',
  receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原inbound收货命令',
  source_version BIGINT NOT NULL DEFAULT 0 COMMENT '已生效累计质检版本',
  accepted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '本批累计合格数量',
  rejected_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '本批累计不合格数量',
  putaway_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '本批已过账上架数量',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT '创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT '最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_receipt_quality (enterprise_id, warehouse_id, receipt_command_id),
  CONSTRAINT ck_stock_receipt_quality CHECK (source_version >= 0 AND accepted_qty >= putaway_qty
    AND rejected_qty >= 0 AND putaway_qty >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货分批质检与上架的库存权威数量边界';
