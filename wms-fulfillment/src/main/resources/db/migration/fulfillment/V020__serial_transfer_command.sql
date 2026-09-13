ALTER TABLE transfer_line ADD COLUMN serial_execution TINYINT NOT NULL DEFAULT 0 COMMENT '首次序列命令固定为1，此后数量入口不能旁路身份核验',
 ADD CONSTRAINT ck_transfer_line_serial_execution CHECK(serial_execution IN (0,1));
-- 保留列名兼容旧应用；事件类型区分分配尝试与调拨命令，不跨类型解释身份。
ALTER TABLE fulfillment_outbox MODIFY COLUMN attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '事件聚合原标识：分配事件为attempt，SerialTransferCommandV1为原调拨命令';
CREATE TABLE transfer_serial_command (
 id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '按企业仓动作和原操作键生成的命令摘要',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '源发出或目的收货仓',
 transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原调拨总单',
 line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原调拨行',
 action VARCHAR(16) COLLATE utf8mb4_bin NOT NULL COMMENT 'ISSUE或RECEIVE',
 quantity INT NOT NULL COMMENT '原SN集合大小，1至200',
 context_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '发给库存的完整命令摘要',
 payload JSON NOT NULL COMMENT '不可变V1单据桶身份命令',
 authorization_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '目的收货原额度；源发出为空',
 authorization_version BIGINT NULL COMMENT '原额度版本，禁止恢复时猜测',
 state VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING等待库存登记完成；COMPLETE已累计真实数量',
 created_at DATETIME(6) NOT NULL COMMENT 'UTC原命令创建时间',
 updated_at DATETIME(6) NOT NULL COMMENT 'UTC完成回执时间',
 PRIMARY KEY(id),
 UNIQUE KEY uk_transfer_serial_authorization(enterprise_id,authorization_id),
 KEY idx_transfer_serial_line(enterprise_id,transfer_id,line_id,action,state),
 CONSTRAINT ck_transfer_serial_quantity CHECK(quantity BETWEEN 1 AND 200),
 CONSTRAINT ck_transfer_serial_action CHECK(action IN ('ISSUE','RECEIVE')),
 CONSTRAINT ck_transfer_serial_state CHECK(state IN ('PENDING','COMPLETE')),
 CONSTRAINT ck_transfer_serial_auth CHECK((action='ISSUE' AND authorization_id IS NULL AND authorization_version IS NULL) OR (action='RECEIVE' AND authorization_id IS NOT NULL AND authorization_version IS NOT NULL AND authorization_version>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公开序列调拨命令，原请求与可靠回执同库关联';
CREATE TABLE transfer_serial_member (
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 transfer_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原调拨总单',
 serial_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原规范化SN，同调拨不得重复发出或跨行占用',
 line_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原调拨行',
 owner_epoch BIGINT NOT NULL COMMENT '源发出原归属代际',
 source_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '持久源命令',
 receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '唯一目的收货命令，指定后不可换批',
 created_at DATETIME(6) NOT NULL COMMENT 'UTC源集合固定时间',
 PRIMARY KEY(enterprise_id,transfer_id,serial_id),
 KEY idx_transfer_serial_source(source_command_id),
 KEY idx_transfer_serial_receipt(receipt_command_id),
 CONSTRAINT ck_transfer_serial_epoch CHECK(owner_epoch>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调拨数量与逐SN原epoch及分批身份的唯一绑定';
