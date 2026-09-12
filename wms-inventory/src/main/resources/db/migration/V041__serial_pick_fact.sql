CREATE TABLE serial_pick_fact (
 id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原库存PICK命令及SN稳定事实',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '执行仓范围',
 command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原来源PICK命令',
 operation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原库存流水操作',
 allocation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原预占分配',
 attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原预占尝试',
 order_line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原预占业务订单行',
 sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原SKU',
 serial_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化SN',
 owner_epoch BIGINT NOT NULL COMMENT '本次身份归属代际',
 source_balance_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '实际拣出数量桶',
 target_balance_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '实际暂存数量桶',
 state VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PICKED' COMMENT 'PICKED原拣货已提交，RELEASED仅供明确补偿释放',
 active_serial VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN state='RELEASED' THEN NULL ELSE serial_id END) STORED COMMENT '已补偿历史不占活跃唯一键，原事实不删除',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '进度并发版本',
 created_at DATETIME(6) NOT NULL COMMENT '原PICK提交UTC时刻',
 updated_at DATETIME(6) NOT NULL COMMENT '最后进度UTC时刻',
 PRIMARY KEY(id),
 UNIQUE KEY uk_serial_pick_fact(enterprise_id,warehouse_id,command_id,serial_id),
 UNIQUE KEY uk_serial_pick_owner(enterprise_id,warehouse_id,sku_id,owner_epoch,active_serial),
 KEY idx_serial_pick_reservation(enterprise_id,warehouse_id,allocation_id,attempt_id,order_line_id,state),
 CONSTRAINT ck_serial_pick_fact_state CHECK(state IN ('PICKED','RELEASED')),
 CONSTRAINT ck_serial_pick_fact_version CHECK(owner_epoch>=0 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='具体SN对应原订单预占的拣货事实，随数量同事务提交';
-- 选择查询先限定数量桶和授权状态，再按SN做键集分页，避免扫描整仓身份。
CREATE INDEX idx_local_serial_selection ON local_serial(enterprise_id,warehouse_id,balance_id,state,serial_id);
