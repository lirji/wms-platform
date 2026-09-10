-- S2-02 库存余额、流水与仓级预占。计划文件名 V002 已被 operator_grant 占用，本文件为 V004。
-- 不含 stock_command/permit/outbox（S2-04/S2-04a）。

CREATE TABLE stock_balance (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '库存桶标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  owner_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '货权主体',
  location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '实物库位',
  sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '商品标识',
  lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '批次标识，无批次使用固定非空标识',
  quality_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '质量状态稳定编码',
  on_hand_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '仓内登记实物量',
  reserved_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '仍占用当前桶的预占量',
  free_execution_claim_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '未预占实物动作的执行占用量',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '余额并发及流水版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_dimension
    (enterprise_id, warehouse_id, owner_id, location_id, sku_id, lot_id, quality_code),
  KEY idx_stock_allocate
    (enterprise_id, warehouse_id, owner_id, sku_id, quality_code, lot_id, location_id),
  CONSTRAINT ck_stock_quantity CHECK (
    on_hand_qty >= 0 AND reserved_qty >= 0 AND free_execution_claim_qty >= 0
    AND reserved_qty + free_execution_claim_qty <= on_hand_qty
  ),
  CONSTRAINT ck_stock_balance_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓级权威库存余额，禁止跨业务直接改写';

CREATE TABLE stock_ledger (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '流水行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '服务端库存操作身份',
  entry_no INT NOT NULL COMMENT '同一操作内分录序号',
  balance_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '被记账的库存桶',
  on_hand_delta DECIMAL(20,6) NOT NULL COMMENT '实物增量，可负',
  reserved_delta DECIMAL(20,6) NOT NULL COMMENT '预占增量，可负',
  free_execution_claim_delta DECIMAL(20,6) NOT NULL COMMENT '自由执行占用增量，可负',
  on_hand_after DECIMAL(20,6) NOT NULL COMMENT '记账后实物量',
  reserved_after DECIMAL(20,6) NOT NULL COMMENT '记账后预占量',
  free_execution_claim_after DECIMAL(20,6) NOT NULL COMMENT '记账后自由执行占用量',
  balance_version BIGINT NOT NULL COMMENT '对应余额版本，同一桶版本不可重复',
  reason_code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '记账原因稳定编码',
  document_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源单据标识',
  actor_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '操作人标识',
  occurred_at DATETIME(6) NOT NULL COMMENT '业务发生UTC时刻',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC落账时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_ledger_operation (enterprise_id, warehouse_id, operation_id, entry_no),
  UNIQUE KEY uk_stock_ledger_balance_version (enterprise_id, warehouse_id, balance_id, balance_version),
  CONSTRAINT ck_stock_ledger_entry CHECK (entry_no >= 1),
  CONSTRAINT ck_stock_ledger_version CHECK (balance_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变库存流水，禁止更新删除';

CREATE TABLE reservation (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓级预占单标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  allocation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约分配标识',
  attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分配尝试标识',
  request_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化请求SHA-256',
  digest_version INT NOT NULL COMMENT '摘要规范版本，重放必须沿用',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '预占状态稳定编码',
  xid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Seata全局事务标识，长度沿用官方schema',
  branch_id BIGINT NOT NULL COMMENT 'Seata分支标识',
  action_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT 'TCC动作名，所有者不可改绑',
  route_epoch BIGINT NOT NULL COMMENT '路由代际，二阶段必须匹配',
  execution_authorization_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '全局成功后的执行授权，未授权可空',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_reservation_attempt (enterprise_id, warehouse_id, allocation_id, attempt_id),
  UNIQUE KEY uk_reservation_owner (enterprise_id, warehouse_id, xid, branch_id, action_name),
  KEY idx_reservation_watch (enterprise_id, warehouse_id, state, updated_at, id),
  CONSTRAINT ck_reservation_digest CHECK (digest_version >= 1),
  CONSTRAINT ck_reservation_epoch CHECK (route_epoch >= 0),
  CONSTRAINT ck_reservation_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓级预占头，XID/branch/action所有者不可改绑';

CREATE TABLE reservation_line (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '预占明细标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  reservation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓级预占单',
  parent_line_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '桶拆分血缘，源行可空',
  order_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约行标识',
  balance_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '占用的库存桶',
  requested_qty DECIMAL(20,6) NOT NULL COMMENT '当前分配到本桶的份额',
  remaining_qty DECIMAL(20,6) NOT NULL COMMENT '尚未消费或释放的剩余量',
  picked_qty DECIMAL(20,6) NOT NULL COMMENT '已拣未发量，是remaining的子集',
  consumed_qty DECIMAL(20,6) NOT NULL COMMENT '已发运消费量',
  released_qty DECIMAL(20,6) NOT NULL COMMENT '已业务释放量',
  inflight_qty DECIMAL(20,6) NOT NULL COMMENT '在途执行占用，是remaining的子集',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  KEY idx_reservation_line_head (enterprise_id, warehouse_id, reservation_id, id),
  CONSTRAINT ck_reservation_line_qty CHECK (
    requested_qty >= 0 AND remaining_qty >= 0 AND picked_qty >= 0 AND consumed_qty >= 0
    AND released_qty >= 0 AND inflight_qty >= 0
    AND requested_qty = remaining_qty + consumed_qty + released_qty
    AND picked_qty <= remaining_qty
    AND inflight_qty <= remaining_qty
  ),
  CONSTRAINT ck_reservation_line_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓级预占明细，部分消费保持头状态直到剩余为0';
