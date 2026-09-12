-- 新运行链路明确货主；旧NULL表示未知，不猜测回填或自动下发授权。
ALTER TABLE fulfillment_order ADD COLUMN owner_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '创建时固定的货主，旧未知记录不能自动执行';
-- 与成功屏障同事务固化投递快照，保留旧payload与重放契约。
ALTER TABLE fulfillment_outbox ADD COLUMN delivery_payload JSON NULL COMMENT 'V1不可变授权投递快照，旧缺失不自动补齐';
CREATE INDEX idx_fulfillment_outbox_lease ON fulfillment_outbox(status,lease_until,event_id);
