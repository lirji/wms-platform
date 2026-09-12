CREATE TABLE serial_shipment (
 id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原身份发运稳定事实标识',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 sku_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原商品',
 normalized_serial VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '规范化序列号',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '实际发运仓',
 owner_epoch BIGINT NOT NULL COMMENT '已消费的原归属代际',
 shipment_ref VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原库存发运事实，不是盘亏引用',
 created_at DATETIME(6) NOT NULL COMMENT '登记提交UTC时刻',
 PRIMARY KEY(id),
 UNIQUE KEY uk_serial_shipment_fact(enterprise_id,sku_id,normalized_serial,shipment_ref),
 UNIQUE KEY uk_serial_shipment_epoch(enterprise_id,sku_id,normalized_serial,owner_epoch),
 CONSTRAINT ck_serial_shipment_epoch CHECK(owner_epoch>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='不可变序列发运证明，后续生命周期不能覆盖历史';
