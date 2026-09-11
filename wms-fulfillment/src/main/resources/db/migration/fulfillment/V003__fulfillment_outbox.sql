-- S4-05 履约屏障 Outbox。与 ALLOCATED 同本地事务；不在 TCC 内派发设备。

CREATE TABLE fulfillment_outbox (
  event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属分配尝试',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓级事件的仓；attempt级使用固定标识NO_WAREHOUSE',
  event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件类型稳定编码',
  operation_id CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '幂等操作键SHA-256',
  payload JSON NOT NULL COMMENT '建单或执行授权请求正文',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '投递状态，本切片只写PENDING',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_fulfillment_outbox_op (enterprise_id, attempt_id, event_type, warehouse_id),
  KEY idx_fulfillment_outbox_attempt (enterprise_id, attempt_id, status),
  CONSTRAINT ck_fulfillment_outbox_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约屏障Outbox，创建出库单/执行授权，禁止TCC内派发设备';
