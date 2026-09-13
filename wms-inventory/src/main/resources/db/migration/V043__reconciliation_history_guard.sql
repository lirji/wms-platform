CREATE TABLE reconciliation_history_guard (
 id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定范围记录主键，随仓迁移',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库存企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库存仓范围',
 closed_before DATETIME(6) NULL COMMENT '已冻结历史的UTC排他上界，未关窗时为空',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '单调推进的关窗版本',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '范围首次建立的UTC时刻',
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '关窗更新的UTC时刻，用于仓迁移增量追平',
 PRIMARY KEY(id),
 UNIQUE KEY uk_reconciliation_history_scope(enterprise_id,warehouse_id),
 CONSTRAINT ck_reconciliation_history_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流水和过账写入共享锁与历史关窗排他锁，旧写节点退出后启用';
