-- TC身份绑定来源必须显式保存，不按当前配置补写旧attempt的权威来源。
CREATE TABLE allocation_tc_binding (
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 attempt_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '唯一分配尝试',
 xid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'TC原始全局事务标识',
 launch_epoch BIGINT NOT NULL COMMENT '绑定时的启动代际',
 cluster_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '受控TC环境与集群稳定标识',
 application_id VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '发起事务的受控TM应用身份',
 transaction_group VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT '发起事务的TC事务分组',
 created_at DATETIME(6) NOT NULL COMMENT 'UTC绑定时刻',
 PRIMARY KEY (enterprise_id,attempt_id),
 UNIQUE KEY uk_tc_binding_xid (cluster_id,xid),
 CONSTRAINT ck_tc_binding_epoch CHECK(launch_epoch > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TC审计来源绑定，不是本地全局事务决定';
CREATE TABLE allocation_recovery_cursor (
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '独立恢复扫描企业范围',
 last_attempt_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '上次完成观察的attempt，不代表TC终态',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发推进版本，只增不减',
 updated_at DATETIME(6) NOT NULL COMMENT 'UTC检查点提交时刻',
 PRIMARY KEY (enterprise_id),
 CONSTRAINT ck_allocation_scan_version CHECK(version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分配恢复有界轮询检查点，不替代TC证据';
CREATE INDEX idx_allocation_recovery_page ON allocation_attempt(enterprise_id,id,state);
