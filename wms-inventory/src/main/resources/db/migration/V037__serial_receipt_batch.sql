CREATE TABLE serial_receipt_batch (
    id VARCHAR(36) COLLATE utf8mb4_bin NOT NULL COMMENT '全局稳定记录标识，供仓迁移游标及跨范围冲突校验',
    enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业隔离范围',
    warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原收货仓',
    receipt_command_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源RECEIVE命令，固定收货批次',
    context_hash CHAR(64) NOT NULL COMMENT '原完整库存上下文及规范身份清单摘要',
    observation_json JSON NOT NULL COMMENT '不可改写的V1完整序列号观察',
    identity_count SMALLINT NOT NULL COMMENT '本批次身份数量，每身份一个基本单位',
    state VARCHAR(16) NOT NULL COMMENT 'PENDING/APPLIED/CANCELLED/REJECTED/UNKNOWN；与命令及身份同事务',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC原批次绑定时间',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC最近业务状态时间',
    PRIMARY KEY(id),
    UNIQUE KEY uk_serial_receipt_batch(enterprise_id,warehouse_id,receipt_command_id),
    CONSTRAINT ck_serial_receipt_count CHECK(identity_count BETWEEN 1 AND 200),
    CONSTRAINT ck_serial_receipt_state CHECK(state IN ('PENDING','APPLIED','CANCELLED','REJECTED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收货分批的完整序列号身份事实；禁止给旧过账猜测补名单';
