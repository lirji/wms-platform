-- 取消数量只是已受理释放意图，收到库存凭证后才计为已同步；不伪造历史取消的回执。
ALTER TABLE outbound_line ADD COLUMN cancelled_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0
    COMMENT '取消未拣已收到库存释放凭证的数量，单位base_unit',
    ADD CONSTRAINT ck_outbound_cancel_posted CHECK (cancelled_posted_qty >= 0 AND cancelled_posted_qty <= cancelled_qty);

-- 单行多次拣发不扫描完整历史；本桶额度随原PICK回执和SHIP受理分别原子增加。
CREATE TABLE outbound_bucket_progress (
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
    warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属仓范围',
    line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源出库内部行ID，货主商品单位由原行固定',
    location_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '已拣集货库位',
    lot_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原实际批次或显式NO_LOT',
    picked_posted_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '本桶已收到库存凭证的拣货量，单位为原行base_unit',
    shipped_physical_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '本桶已受理发运量，包含待回执发运，单位为原行base_unit',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '本桶额度并发版本',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC首次回执时刻',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后额度变化时刻',
    PRIMARY KEY (enterprise_id,warehouse_id,line_id,location_id,lot_id),
    CONSTRAINT ck_outbound_bucket_qty CHECK(picked_posted_qty>=0 AND shipped_physical_qty>=0
        AND shipped_physical_qty<=picked_posted_qty AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库已过账拣货的桶级发运额度，不是库存权威余额';
