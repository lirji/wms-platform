-- S4-03 仓级库存库 Seata 2.6.0 原生 Fence。必须与 reservation/余额同一物理库、同一本地事务。

CREATE TABLE tcc_fence_log (
  xid VARCHAR(128) NOT NULL COMMENT 'Seata全局事务标识',
  branch_id BIGINT NOT NULL COMMENT 'Seata分支事务标识',
  action_name VARCHAR(64) NOT NULL COMMENT 'TCC动作名',
  status TINYINT NOT NULL COMMENT 'Fence阶段：1尝试2提交3回滚4悬挂',
  gmt_create DATETIME(3) NOT NULL COMMENT '记录创建时刻',
  gmt_modified DATETIME(3) NOT NULL COMMENT '记录变更时刻',
  PRIMARY KEY (xid, branch_id),
  KEY idx_gmt_modified (gmt_modified),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seata TCC Fence，与预占同库同事务，禁止单独提交';
