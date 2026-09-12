-- 拣货规划幂等键与任务同事务持久化；旧任务保持NULL，既有执行路径仍可读取。
ALTER TABLE outbound_task ADD COLUMN planning_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL
    COMMENT '规划命令幂等键，旧任务和回库任务可空';
CREATE UNIQUE INDEX uk_outbound_task_planning ON outbound_task (enterprise_id, warehouse_id, planning_command_id);
CREATE INDEX idx_outbound_task_line_state ON outbound_task (enterprise_id, warehouse_id, document_line_id, task_type, state);
