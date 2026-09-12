-- 命令先占键、后绑定attempt，所有写入由同一事务提交，丢回执重试返回原attempt。
CREATE TABLE fulfillment_attempt_command (
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业隔离范围',
    command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '客户端幂等命令键',
    fulfillment_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原始履约单',
    payload_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '版本化规范请求SHA256摘要',
    claim_id CHAR(36) COLLATE utf8mb4_bin NOT NULL COMMENT '首次写入随机身份，区别并发重放',
    attempt_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '同事务绑定的原attempt，不按当前活动attempt猜测',
    created_at DATETIME(6) NOT NULL COMMENT '首次受理UTC时间',
    PRIMARY KEY (enterprise_id, command_id),
    UNIQUE KEY uk_attempt_command_attempt (enterprise_id, attempt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='创建分配attempt的不可变命令回执';
