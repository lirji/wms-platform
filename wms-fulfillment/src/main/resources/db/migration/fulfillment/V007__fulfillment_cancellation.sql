-- 履约取消请求。只记录受理，不在本表发明 TCC 回滚或 ALLOCATED。

CREATE TABLE fulfillment_cancellation (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '取消请求标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  fulfillment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约单',
  client_operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '与 Idempotency-Key 一致',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当时活动 attempt，无活动可空',
  reason VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '取消原因，可空',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '请求人',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'CANCEL_REQUESTED，不是业务终态成功',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fulfillment_cancellation_op (enterprise_id, client_operation_id),
  KEY idx_fulfillment_cancellation_order (enterprise_id, fulfillment_id),
  CONSTRAINT ck_fulfillment_cancellation_ver CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约取消受理，不替代 TC Cancel';
