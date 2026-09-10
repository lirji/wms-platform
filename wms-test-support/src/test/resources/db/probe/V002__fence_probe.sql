CREATE TABLE tcc_fence_log (
    xid VARCHAR(128) NOT NULL COMMENT 'Seata全局事务标识',
    branch_id BIGINT NOT NULL COMMENT 'Seata分支事务标识',
    action_name VARCHAR(64) NOT NULL COMMENT '注册动作名',
    status TINYINT NOT NULL COMMENT 'Fence阶段：1尝试2提交3回滚4悬挂',
    gmt_create DATETIME(3) NOT NULL COMMENT '记录创建时刻',
    gmt_modified DATETIME(3) NOT NULL COMMENT '记录变更时刻',
    PRIMARY KEY (xid, branch_id),
    KEY idx_gmt_modified (gmt_modified),
    KEY idx_status (status)
) COMMENT='S0 Seata 2.6.0原生Fence兼容探针，仅测试库';
