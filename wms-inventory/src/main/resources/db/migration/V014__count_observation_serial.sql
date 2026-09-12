-- S6-03a 盘点序列号观察集合。原扫描不可覆盖。

CREATE TABLE count_observation_serial (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '观察身份行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  observation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属点数观察',
  normalized_serial VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化序列号',
  serial_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已登记身份，未知可空',
  presence_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'PRESENT/FOUND/MISSING',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_count_observation_serial (enterprise_id, warehouse_id, observation_id, normalized_serial),
  KEY idx_count_observation_serial_obs (enterprise_id, warehouse_id, observation_id),
  CONSTRAINT ck_count_observation_serial_ver CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点一轮观察到的序列号集合';
