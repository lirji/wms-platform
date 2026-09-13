CREATE TABLE serial_transfer_command (
 id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '履约生成的原调拨命令标识',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '本次源或目的库存仓',
 context_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '完整原命令摘要，重复不得换身份',
 payload JSON NOT NULL COMMENT '原单行、仓、桶及逐身份epoch的V1命令',
 state VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING等待登记恢复；COMPLETE已有可靠完成回执',
 next_check_at DATETIME(6) NOT NULL COMMENT 'UTC下次有界完成核验时间',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '本地完成并发版本',
 created_at DATETIME(6) NOT NULL COMMENT 'UTC原业务效果提交时间',
 updated_at DATETIME(6) NOT NULL COMMENT 'UTC核验或完成时间',
 PRIMARY KEY(id),
 UNIQUE KEY uk_serial_transfer_command_scope(enterprise_id,warehouse_id,id),
 KEY idx_serial_transfer_command_ready(enterprise_id,warehouse_id,state,next_check_at,id),
 CONSTRAINT ck_serial_transfer_command_state CHECK(state IN ('PENDING','COMPLETE')),
 CONSTRAINT ck_serial_transfer_command_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='序列调拨库存效果与登记完成回执的持久关联';
