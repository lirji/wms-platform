ALTER TABLE reconciliation_history_guard
 ADD COLUMN active_cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '唯一活动采集窗口，完成或取消后释放';

ALTER TABLE reconciliation_cutoff
 ADD COLUMN collection_state VARCHAR(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'UNREQUESTED' COMMENT 'UNREQUESTED/PENDING/RUNNING/ISOLATED/COMPLETE/CANCELLED',
 ADD COLUMN collection_progress JSON NULL COMMENT '版本1有界来源分页及反向库存摘要检查点',
 ADD COLUMN claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '单调领取代际，旧回执不得推进新租约',
 ADD COLUMN collection_attempts INT NOT NULL DEFAULT 0 COMMENT '当前检查点连续尝试次数，推进后归零',
 ADD COLUMN next_attempt_at DATETIME(6) NULL COMMENT '下次可领取时刻或当前租约截止UTC',
 ADD COLUMN collection_error VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '受控错误码，不保存外部正文或敏感信息',
 ADD INDEX idx_reconciliation_latest(enterprise_id,warehouse_id,collection_state,closed_at,cutoff_id),
 ADD CONSTRAINT ck_reconciliation_collection_state CHECK(collection_state IN ('UNREQUESTED','PENDING','RUNNING','ISOLATED','COMPLETE','CANCELLED')),
 ADD CONSTRAINT ck_reconciliation_collection_budget CHECK(claim_epoch>=0 AND collection_attempts BETWEEN 0 AND 12);

CREATE TABLE reconciliation_collection_audit (
 id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '审计事件主键，随仓迁移',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓范围',
 cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '被操作的原关窗身份',
 action VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'REQUEST/RETRY/CANCEL，取消不撤销已提交业务',
 actor_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '已鉴权的操作主体',
 reason VARCHAR(512) NOT NULL COMMENT '人工操作原因，不允许保存凭据',
 claim_epoch BIGINT NOT NULL COMMENT '操作后的领取代际',
 created_at DATETIME(6) NOT NULL COMMENT '审计提交UTC时间',
 PRIMARY KEY(id),
 KEY idx_reconciliation_audit_scope(enterprise_id,warehouse_id,cutoff_id,created_at,id),
 CONSTRAINT ck_reconciliation_audit_action CHECK(action IN ('REQUEST','RETRY','CANCEL')),
 CONSTRAINT ck_reconciliation_audit_epoch CHECK(claim_epoch>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可信水位采集操作审计，审计与状态变更原子提交';
