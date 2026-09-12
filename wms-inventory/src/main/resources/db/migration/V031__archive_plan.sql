CREATE TABLE archive_plan (
    id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '归档候选计划标识',
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业',
    warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓',
    run_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调度幂等运行键',
    policy_ref VARCHAR(256) NOT NULL COMMENT '运维提供的保留依据引用，计划不代表删除审批',
    cutoff_at DATETIME(6) NOT NULL COMMENT '明确提供的候选关闭时刻，不推算保留天数',
    cursor_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已提交流水游标',
    candidate_count BIGINT NOT NULL DEFAULT 0 COMMENT '本计划已枚举候选数',
    manifest_hash CHAR(64) NOT NULL COMMENT '有序候选标识和行摘要的链式SHA256',
    state VARCHAR(32) NOT NULL COMMENT 'PLANNING或PLANNED_EXPORT，均未导出或删除',
    requested_by VARCHAR(128) NOT NULL COMMENT '触发计划的受信主体',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
    created_at DATETIME(6) NOT NULL COMMENT '计划请求时刻',
    updated_at DATETIME(6) NOT NULL COMMENT '检查点提交时刻',
    PRIMARY KEY(id),
    UNIQUE KEY uk_archive_run (enterprise_id,warehouse_id,run_key),
    CONSTRAINT ck_archive_plan CHECK (candidate_count>=0 AND version>=0 AND state IN ('PLANNING','PLANNED_EXPORT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存不可变流水的归档候选计划，禁止据此直接删除';
CREATE TABLE archive_plan_item (
    id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '计划项标识',
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业',
    warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓',
    plan_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '归档候选计划',
    ledger_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原始库存流水标识，身份保留',
    payload_hash CHAR(64) NOT NULL COMMENT '原始流水全字段规范JSON的SHA256，导出时重新核对',
    created_at DATETIME(6) NOT NULL COMMENT '枚举时刻',
    PRIMARY KEY(id),
    UNIQUE KEY uk_archive_item (enterprise_id,warehouse_id,plan_id,ledger_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归档候选清单，只保存引用及摘要，不执行源记录删除';
CREATE INDEX idx_archive_ledger ON stock_ledger (enterprise_id,warehouse_id,id,created_at);
