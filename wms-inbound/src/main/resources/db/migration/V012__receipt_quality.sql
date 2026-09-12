-- 分批质检是新增显式路径；旧行级记录不猜测回填批次。
CREATE TABLE inbound_receipt_quality (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '技术主键',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓',
  receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原收货批次命令身份',
  inbound_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原入库行',
  source_version BIGINT NOT NULL DEFAULT 0 COMMENT '最新受理质检版本',
  applied_version BIGINT NOT NULL DEFAULT 0 COMMENT '库存已确认生效版本',
  accepted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '最新版本累计合格数量',
  rejected_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '最新版本累计不合格数量',
  putaway_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '本批已确认上架实物数量',
  active_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '最新质检命令，首次未检为空',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'OPEN/PENDING/APPLIED/REJECTED等同步状态',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT '登记创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT '最后更新时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_receipt_quality (enterprise_id, warehouse_id, receipt_command_id),
  KEY idx_receipt_quality_line (enterprise_id, warehouse_id, inbound_line_id, receipt_command_id),
  CONSTRAINT ck_receipt_quality_qty CHECK (accepted_qty >= putaway_qty AND rejected_qty >= 0 AND putaway_qty >= 0
    AND source_version >= applied_version AND applied_version >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每个收货批次的质检及上架数量边界';

CREATE TABLE inbound_quality_revision (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '不可变质检结果标识，对应inspectionId',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓',
  receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原收货批次命令',
  source_version BIGINT NOT NULL COMMENT '该批递增质检版本',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '受理的质检命令键',
  request_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次、结论及版本请求摘要',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原始质检人',
  accepted_qty DECIMAL(20,6) NOT NULL COMMENT '本版本累计合格数量',
  rejected_qty DECIMAL(20,6) NOT NULL COMMENT '本版本累计不合格数量',
  created_at DATETIME(6) NOT NULL COMMENT '受理时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quality_revision (enterprise_id, warehouse_id, receipt_command_id, source_version),
  UNIQUE KEY uk_quality_command (enterprise_id, warehouse_id, command_id),
  CONSTRAINT ck_quality_revision CHECK (source_version >= 1 AND accepted_qty >= 0 AND rejected_qty >= 0
    AND accepted_qty + rejected_qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分批质检的不可变版本与幂等审计';
