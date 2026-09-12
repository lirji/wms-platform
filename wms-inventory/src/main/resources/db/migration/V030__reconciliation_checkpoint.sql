CREATE TABLE reconciliation_scan (
    id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '扫描主键',
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业',
    warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓',
    cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '不可变对账窗口',
    balance_cursor VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已提交余额游标',
    fact_cursor VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已提交来源事实游标',
    posting_cursor VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已提交过账游标',
    balance_done TINYINT NOT NULL DEFAULT 0 COMMENT '本轮余额扫描结束',
    fact_done TINYINT NOT NULL DEFAULT 0 COMMENT '本轮来源扫描结束',
    posting_done TINYINT NOT NULL DEFAULT 0 COMMENT '本轮过账扫描结束',
    completed_cycles BIGINT NOT NULL DEFAULT 0 COMMENT '完整扫描次数，不表示差异已修复',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '检查点版本',
    created_at DATETIME(6) NOT NULL COMMENT '创建时刻',
    updated_at DATETIME(6) NOT NULL COMMENT '最近提交时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_recon_scan (enterprise_id, warehouse_id, cutoff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='有界对账检查点；差异和游标同事务提交';
CREATE INDEX idx_recon_balance ON stock_balance (enterprise_id, warehouse_id, id, created_at);
CREATE INDEX idx_recon_fact ON source_execution_fact (enterprise_id, warehouse_id, id, occurred_at);
CREATE INDEX idx_recon_posting ON stock_posting (enterprise_id, warehouse_id, id, created_at);
CREATE INDEX idx_recon_reserved ON reservation_line (enterprise_id, warehouse_id, balance_id, reservation_id);
CREATE INDEX idx_recon_serial ON local_serial (enterprise_id, warehouse_id, balance_id, state);
