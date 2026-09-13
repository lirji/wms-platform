CREATE TABLE inventory_tcc_terminal (
 id VARCHAR(36) NOT NULL COMMENT '对应唯一原RM意图，迁移不改变',
 enterprise_id VARCHAR(64) NOT NULL COMMENT '原企业',
 warehouse_id VARCHAR(64) NOT NULL COMMENT '原参与仓',
 xid VARCHAR(128) NOT NULL COMMENT '经只读审计确认的原TC事务',
 branch_id BIGINT NOT NULL COMMENT '原RM持久化分支',
 action_name VARCHAR(128) NOT NULL COMMENT '原TC资源身份',
 terminal_status TINYINT NOT NULL COMMENT 'TC终态9提交11回滚13超时回滚',
 proof_json JSON NOT NULL COMMENT '原集群、TM、组、attempt及终态通知',
 proof_hash CHAR(64) NOT NULL COMMENT '原通知规范摘要，禁止覆盖',
 created_at DATETIME(6) NOT NULL COMMENT 'UTC原证据持久化时刻',
 updated_at DATETIME(6) NOT NULL COMMENT 'UTC迁移水位，重复不更新',
 PRIMARY KEY(id),
 UNIQUE KEY uk_inventory_terminal_branch(xid,branch_id),
 KEY idx_inventory_terminal_scope(enterprise_id,warehouse_id,id),
 CONSTRAINT ck_inventory_terminal_status CHECK(terminal_status IN (9,11,13)),
 CONSTRAINT ck_inventory_terminal_branch CHECK(branch_id>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原RM与TC终态证明，只可追加；不是二阶段决定';
