-- S9-02 仓路由与短暂停写迁移。旧 epoch 在本库拒绝，不能只改缓存。

CREATE TABLE warehouse_route (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '路由记录标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  cell_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '当前或源 Cell',
  target_cell_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '迁移目标 Cell，未迁移可空',
  route_epoch BIGINT NOT NULL COMMENT '路由代际，切换后旧实例必须拒绝',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'ACTIVE/COPYING/QUIESCING/RETIRED',
  cutoff_at DATETIME(6) NULL COMMENT '最近一次全量或增量拷贝水位',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouse_route (enterprise_id, warehouse_id),
  CONSTRAINT ck_warehouse_route_epoch CHECK (route_epoch >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓路由写令牌，切换后源库旧 epoch 拒写';
