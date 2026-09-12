-- 只追加索引，不改已执行迁移；主数据使用稳定主键顺序，单据使用时间与主键倒序。
CREATE INDEX idx_count_plan_page ON count_plan (enterprise_id, warehouse_id, created_at DESC, id DESC);
CREATE INDEX idx_job_run_page ON job_run (enterprise_id, warehouse_id, created_at DESC, id DESC);
CREATE INDEX idx_warehouse_page ON warehouse (enterprise_id, id);
CREATE INDEX idx_sku_page ON sku (enterprise_id, id);
CREATE INDEX idx_sku_unit_page ON sku_unit (enterprise_id, sku_id, id);
CREATE INDEX idx_lot_page ON lot (enterprise_id, warehouse_id, id);
CREATE INDEX idx_inventory_view_page ON inventory_view (enterprise_id, warehouse_id, generation, id);
CREATE INDEX idx_inventory_view_sku_page ON inventory_view (enterprise_id, warehouse_id, generation, sku_id, id);
CREATE INDEX idx_reconciliation_case_page ON reconciliation_case (enterprise_id, warehouse_id, cutoff_id, id);
