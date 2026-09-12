-- 入库单按行定位收货效果，避免按仓扫描全部来源事实。
CREATE INDEX idx_source_receipt_line ON source_effect (enterprise_id,warehouse_id,source_service,action,fact_line_id,id);
