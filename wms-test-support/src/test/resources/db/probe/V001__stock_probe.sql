CREATE TABLE stock_probe (
    warehouse_id VARCHAR(16) NOT NULL COMMENT '隔离探针仓标识，不是生产库存',
    sku_id VARCHAR(32) NOT NULL COMMENT '隔离探针商品标识',
    on_hand BIGINT NOT NULL COMMENT '探针实物数量',
    reserved BIGINT NOT NULL DEFAULT 0 COMMENT '探针占用数量',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '并发更新版本',
    PRIMARY KEY (warehouse_id, sku_id),
    CONSTRAINT ck_probe_quantity CHECK (on_hand >= reserved AND reserved >= 0)
) COMMENT='S0分片和本地事务兼容探针，仅隔离环境使用';
