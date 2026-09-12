-- 分段内容与检查点在同一事务提交；失败恢复不会跳过或重复库存桶。
ALTER TABLE reconciliation_snapshot
  ADD COLUMN last_balance_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已提交分段的最后库存桶，稳定游标',
  ADD COLUMN part_count INT NOT NULL DEFAULT 0 COMMENT '已提交分段数',
  ADD COLUMN total_rows BIGINT NOT NULL DEFAULT 0 COMMENT '已提交数量事实行数',
  ADD COLUMN units_json JSON NULL COMMENT '已导出单位集合，不臆造统一单位';
CREATE INDEX idx_ledger_snapshot_cutoff ON stock_ledger
  (enterprise_id, warehouse_id, balance_id, created_at, balance_version);
