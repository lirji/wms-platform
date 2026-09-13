CREATE TABLE fulfillment_cancellation_result (
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原企业',
 attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原已提交尝试',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '已补偿参与仓',
 cancellation_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原取消决定',
 state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'COMPLETED或PARTIALLY_COMPENSATED，不代表TCC回滚',
 payload JSON NOT NULL COMMENT '原释放回执汇总及已执行数量',
 payload_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '不可变回执摘要',
 created_at DATETIME(6) NOT NULL COMMENT '回执入库UTC时刻',
 PRIMARY KEY(enterprise_id,attempt_id,warehouse_id),
 CONSTRAINT ck_cancel_result_state CHECK(state IN ('COMPLETED','PARTIALLY_COMPENSATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逐仓业务补偿完成证明，未知实物不产生完成回执';

ALTER TABLE allocation_attempt ADD COLUMN compensation_cancellation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '首次已提交补偿决定，后续取消请求沿用';

ALTER TABLE allocation_execution DROP CHECK ck_execution_state,
 ADD CONSTRAINT ck_execution_state CHECK(state IN ('READY','BEGIN_CALLING','BEGIN_UNKNOWN','TRYING','FINISH_REQUESTED','WAITING_TERMINAL','COMPLETED','ROLLED_BACK','ISOLATED','COMPENSATING','COMPENSATED'));
