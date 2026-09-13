-- 旧意图保持原协议，不推测补填目的仓。新公开调拨与源封闭原子固定准备上下文。
ALTER TABLE serial_release_intent
 ADD COLUMN target_warehouse_id VARCHAR(64) NULL COMMENT '公开调拨原目的仓；NULL表示旧版仅传播释放的意图',
 ADD CONSTRAINT ck_serial_release_target CHECK (target_warehouse_id IS NULL OR target_warehouse_id <> warehouse_id);
