-- S7-04 稳定 cutoff 内部对账。不直接改写余额；缺水位不得判丢失。

CREATE TABLE reconciliation_cutoff (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '窗口记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定对账窗口标识',
  closed_at DATETIME(6) NOT NULL COMMENT '窗口关闭时刻，禁止用当前时间冒充',
  source_watermark VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '来源事实提交水位，未齐可空',
  posting_watermark VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '库存过账水位，未齐可空',
  receipt_watermark VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '来源回执消费水位，未齐可空',
  watermarks_complete TINYINT NOT NULL COMMENT '三方水位是否齐全，1齐全0待齐',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '窗口状态',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recon_cutoff (enterprise_id, warehouse_id, cutoff_id),
  CONSTRAINT ck_recon_cutoff CHECK (watermarks_complete IN (0, 1) AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部对账稳定窗口与三方水位';

CREATE TABLE reconciliation_case (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '差异单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属稳定窗口',
  case_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '差异类型稳定编码',
  discrepancy_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '判差码',
  scope_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '桶或命令范围',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '商品，窗口级差异可空',
  expected_qty DECIMAL(20,6) NOT NULL COMMENT '窗口内应有量',
  actual_qty DECIMAL(20,6) NOT NULL COMMENT '窗口内实有量',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '差异单状态',
  evidence_ref VARCHAR(2000) COLLATE utf8mb4_bin NOT NULL COMMENT '首次证据，创建后不覆盖',
  remediation_operation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '审批后的业务命令，未修可空',
  approved_by VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '审批人，未批可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_recon_case (enterprise_id, warehouse_id, cutoff_id, case_type, scope_id),
  KEY idx_recon_case_state (enterprise_id, warehouse_id, cutoff_id, state, id),
  CONSTRAINT ck_recon_case CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部差异工作台，修复走业务入口';

CREATE TABLE source_execution_fact (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源事实标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  source_service VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源服务稳定编码',
  command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源命令身份',
  business_effect_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库存效果业务键',
  fact_kind VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'PHYSICAL或POSTED，禁止混比',
  quantity DECIMAL(20,6) NOT NULL COMMENT '来源申报数量',
  occurred_at DATETIME(6) NOT NULL COMMENT '来源事实发生时刻',
  watermark_token VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '该事实所属提交水位',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_source_fact (enterprise_id, warehouse_id, source_service, command_id, fact_kind),
  KEY idx_source_fact_effect (enterprise_id, warehouse_id, business_effect_key, fact_kind),
  CONSTRAINT ck_source_fact CHECK (quantity >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源实物或过账事实，供三方水位核对';
