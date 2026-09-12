-- 旧任务引用保持空，不猜测其原收货批次；新版分批任务必须在T1绑定。
ALTER TABLE inbound_task
  ADD COLUMN receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '本上架任务的原收货批次命令，旧任务未绑定为空',
  ADD KEY idx_task_receipt (enterprise_id,warehouse_id,receipt_command_id,id);
