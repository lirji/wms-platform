CREATE TABLE inbound_serial_putaway (
    id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL COMMENT '不可变上架身份占用记录标识',
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业隔离范围',
    warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原收货和上架仓',
    receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原收货批次命令',
    serial_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规范序列号；同批不能被不同任务重复领取',
    task_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '首次领取该身份的上架任务',
    putaway_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '首次上架来源命令，超时重试不改绑',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC首次受理时间，不是库存已完成时间',
    PRIMARY KEY(id),
    UNIQUE KEY uk_serial_putaway_receipt(enterprise_id,warehouse_id,receipt_command_id,serial_id),
    KEY idx_serial_putaway_task(enterprise_id,warehouse_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源批次逐身份上架额度；与任务和来源命令同事务受理';
