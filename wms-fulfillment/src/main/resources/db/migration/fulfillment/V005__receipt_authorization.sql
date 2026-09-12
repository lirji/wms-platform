-- S6-01a 调拨接收额度 token。未知结果不自动回收；消费/取消与行锁同事务。

CREATE TABLE receipt_authorization (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '接收授权标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调拨总单',
  transfer_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属调拨行',
  target_warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '目的仓库',
  target_client_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '目的仓预先生成的客户端操作键',
  quantity DECIMAL(20,6) NOT NULL COMMENT '授权数量',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'OPEN/CONSUMED/CANCELLED',
  token_version BIGINT NOT NULL DEFAULT 1 COMMENT 'token版本，消费必须匹配',
  target_result_ref VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '目的仓结果引用，未消费可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_receipt_auth_client (enterprise_id, transfer_line_id, target_client_operation_id),
  KEY idx_receipt_auth_transfer (enterprise_id, transfer_id),
  CONSTRAINT ck_receipt_auth_qty CHECK (quantity > 0 AND token_version >= 1 AND version >= 0
    AND state IN ('OPEN','CONSUMED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨接收额度授权，与调拨行同库';

ALTER TABLE transfer_fact DROP CHECK ck_transfer_fact_qty;
ALTER TABLE transfer_fact ADD CONSTRAINT ck_transfer_fact_qty CHECK (
  quantity > 0 AND action IN ('ISSUE','RECEIVE','LOSS') AND version >= 0);
