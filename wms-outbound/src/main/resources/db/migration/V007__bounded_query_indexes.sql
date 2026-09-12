-- 创建时间与主键组成稳定键集；索引与租户查询条件一致。
CREATE INDEX idx_outbound_order_page ON outbound_order (enterprise_id, warehouse_id, created_at DESC, id DESC);
