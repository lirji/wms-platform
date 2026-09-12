-- S8-01 数量事实快照。完成后不可变；不开放核心库账号。

CREATE TABLE reconciliation_snapshot (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '快照标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  scenario_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '对账场景稳定编码',
  cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定窗口标识',
  closed_at DATETIME(6) NOT NULL COMMENT '窗口关闭时刻',
  scope_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '范围规范化SHA-256',
  scope_json VARCHAR(2000) COLLATE utf8mb4_bin NOT NULL COMMENT '范围正文',
  source_watermarks VARCHAR(2000) COLLATE utf8mb4_bin NOT NULL COMMENT '来源水位JSON',
  schema_version INT NOT NULL COMMENT 'WarehouseQuantityFact契约版本',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '快照状态',
  manifest_json VARCHAR(4000) COLLATE utf8mb4_bin NULL COMMENT '完成后的manifest，未完成可空',
  completed_at DATETIME(6) NULL COMMENT '完成时刻，未完成可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recon_snapshot (enterprise_id, warehouse_id, scenario_code, cutoff_id, scope_digest),
  CONSTRAINT ck_recon_snapshot CHECK (schema_version >= 1 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数量对账快照头，成功后内容不可变';

CREATE TABLE snapshot_part (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分片标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  snapshot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属快照',
  part_no INT NOT NULL COMMENT '分片序号从1起',
  payload MEDIUMTEXT NOT NULL COMMENT 'JSONL事实正文，服务内读取不开放库账号',
  row_count BIGINT NOT NULL COMMENT '本片行数',
  sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'payload SHA-256十六进制',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '分片状态',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_snapshot_part (enterprise_id, warehouse_id, snapshot_id, part_no),
  CONSTRAINT ck_snapshot_part CHECK (part_no >= 1 AND row_count >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='快照分片，全部成功后才发布manifest';
