-- S2-03 库存 Outbox 与业务同事务落库。领取/发布/重试在 S2-04。

CREATE TABLE outbox_event (
  event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件标识，跨仓迁移保持不变',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  aggregate_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '聚合类型稳定编码',
  aggregate_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '聚合标识',
  aggregate_version BIGINT NOT NULL COMMENT '聚合版本，与余额版本一致',
  event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件类型稳定编码',
  operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '产生该事件的库存操作',
  payload JSON NOT NULL COMMENT '版本化事件正文，查询字段另有独立列',
  status VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '投递状态稳定编码',
  claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '领取代际，发布器CAS使用',
  lease_until DATETIME(6) NULL COMMENT '领取租约截止UTC时刻，未领取可空',
  next_attempt_at DATETIME(6) NOT NULL COMMENT '下次可领取UTC时刻',
  published_at DATETIME(6) NULL COMMENT '成功发布UTC时刻，未发布可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (event_id),
  KEY idx_outbox_publish (status, next_attempt_at, event_id),
  KEY idx_outbox_operation (enterprise_id, warehouse_id, operation_id),
  CONSTRAINT ck_outbox_version CHECK (version >= 0 AND claim_epoch >= 0 AND aggregate_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存Outbox，与余额流水同物理事务，未发布不得当成功投递';
