-- 出库执行授权。必须先有本库 TCC Committed 证据副本，不能在本接口发明 ALLOCATED。

CREATE TABLE outbound_tcc_evidence (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '证据行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约 attempt',
  xid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'TC 全局 XID',
  tc_observed_status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '观察副本，必须是 Committed 才可授权',
  tc_terminal_evidence_ref VARCHAR(256) COLLATE utf8mb4_bin NOT NULL COMMENT 'TC 终态证据引用',
  participant_set_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '固定参与者集合摘要',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_tcc_evidence (enterprise_id, warehouse_id, attempt_id),
  CONSTRAINT ck_outbound_tcc_evidence_ver CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库侧 TCC 成功屏障证据副本，不是履约 ALLOCATED';

CREATE TABLE outbound_execution_authorization (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '授权记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  outbound_order_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '出库单',
  client_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '与 Idempotency-Key 一致',
  authorization_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '执行授权身份',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '核验的 attempt',
  xid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '核验的 XID',
  tc_terminal_evidence_ref VARCHAR(256) COLLATE utf8mb4_bin NOT NULL COMMENT '核验的证据引用',
  participant_set_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '核验的参与者摘要',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '授权操作人',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'AUTHORIZED',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbound_exec_auth_op (enterprise_id, warehouse_id, client_operation_id),
  UNIQUE KEY uk_outbound_exec_auth_id (enterprise_id, warehouse_id, authorization_id),
  CONSTRAINT ck_outbound_exec_auth_ver CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库执行授权，绑定前必须核对 Committed 证据';
