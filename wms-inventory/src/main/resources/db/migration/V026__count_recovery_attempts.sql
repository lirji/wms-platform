-- 恢复预算与领取代际分开保存；崩溃也消耗预算，旧执行器不能覆盖新领取。
ALTER TABLE count_line
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0 COMMENT '自动恢复已领取次数，上限八次',
    ADD COLUMN recovery_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '自动恢复领取代际，只递增',
    ADD COLUMN recovery_lease_until DATETIME(6) NULL COMMENT '恢复领取租约到期时刻',
    ADD COLUMN recovery_next_at DATETIME(6) NULL COMMENT '失败退避后的下次可恢复时刻',
    ADD COLUMN recovery_error_code VARCHAR(64) NULL COMMENT '最近恢复失败的稳定错误码，不保存异常原文',
    ADD INDEX idx_count_recovery (enterprise_id, warehouse_id, count_plan_id, status, recovery_attempts, recovery_next_at);
