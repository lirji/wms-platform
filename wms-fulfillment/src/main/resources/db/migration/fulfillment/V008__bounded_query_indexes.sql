-- 创建时间与主键组成稳定键集；索引与租户查询条件一致。
CREATE INDEX idx_fulfillment_order_page ON fulfillment_order (enterprise_id, created_at DESC, id DESC);
CREATE INDEX idx_transfer_order_page ON transfer_order (enterprise_id, created_at DESC, id DESC);
