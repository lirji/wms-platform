-- S7-04 过期批次通知。不改 lot.expires_at，不释放预占，不发明 OQ-03。

CREATE TABLE expiry_notice (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '过期通知标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '已确认失效批次',
  window_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '巡检窗口',
  expires_at DATETIME(6) NOT NULL COMMENT '批次已有失效时刻，禁止回填',
  open_reservations INT NOT NULL DEFAULT 0 COMMENT '仍占用的预占数',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_expiry_notice (enterprise_id, warehouse_id, lot_id, window_id),
  CONSTRAINT ck_expiry_notice CHECK (open_reservations >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='过期批次通知，接口仍实时校验效期';
