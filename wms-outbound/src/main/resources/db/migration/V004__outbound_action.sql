-- S5-03 人工/PDA/设备共享动作身份。已固定的 device_command_id 换主后沿用。

ALTER TABLE outbound_task
  ADD COLUMN action_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '共享动作身份，人工PDA设备同一键' AFTER document_line_id,
  ADD COLUMN device_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'STARTED后固定的设备命令，崩溃不得换号',
  ADD UNIQUE KEY uk_outbound_task_action (enterprise_id, warehouse_id, action_id),
  ADD UNIQUE KEY uk_outbound_task_device (enterprise_id, warehouse_id, device_command_id);
