CREATE TABLE reservation_probe (
 enterprise_id VARCHAR(32) NOT NULL COMMENT '企业作用域',
 warehouse_id VARCHAR(16) NOT NULL COMMENT '仓标识',
 allocation_id VARCHAR(64) NOT NULL COMMENT '分配业务键B',
 attempt_id VARCHAR(64) NOT NULL COMMENT '分配尝试，与仓共同构成业务键',
 xid VARCHAR(128) NOT NULL COMMENT '预占所有者XID',
 branch_id BIGINT NOT NULL COMMENT '预占所有者branchId',
 action_name VARCHAR(64) NOT NULL COMMENT '预占所有者actionName',
 request_digest VARCHAR(64) NOT NULL COMMENT '规范化请求摘要，异内容不得改绑',
 reserved_qty BIGINT NOT NULL COMMENT '本次预占数量',
 PRIMARY KEY (enterprise_id,warehouse_id,allocation_id,attempt_id),
 UNIQUE KEY uk_owner (enterprise_id,warehouse_id,xid,branch_id,action_name)
) ENGINE=InnoDB COMMENT='S0重复Try所有权探针，不是正式预占表';
