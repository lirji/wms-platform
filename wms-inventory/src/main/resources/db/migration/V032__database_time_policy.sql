CREATE TABLE database_time_policy (
    id TINYINT NOT NULL COMMENT '单库唯一时间规则标识1',
    storage_offset VARCHAR(6) NOT NULL COMMENT 'DATETIME瞬时字段使用的固定UTC偏移',
    evidence_ref VARCHAR(256) NOT NULL COMMENT '新库初始化或历史时区审计依据',
    registered_at DATETIME(6) NOT NULL COMMENT '规则登记UTC时刻；不使用业务存储偏移',
    PRIMARY KEY(id),
    CONSTRAINT ck_database_time_policy CHECK (id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物理库DATETIME时间语义，变更必须经过独立数据转换';
