CREATE TABLE allocation_slot (
 enterprise_id VARCHAR(32) NOT NULL COMMENT '企业作用域',
 allocation_id VARCHAR(64) NOT NULL COMMENT '业务分配标识',
 active_attempt_id VARCHAR(64) NULL COMMENT '唯一活动尝试',
 PRIMARY KEY(enterprise_id,allocation_id)
) ENGINE=InnoDB COMMENT='S0启动仲裁探针，不是正式履约表';
CREATE TABLE launch_attempt (
 enterprise_id VARCHAR(32) NOT NULL COMMENT '企业作用域',
 attempt_id VARCHAR(64) NOT NULL COMMENT '不可变尝试标识',
 allocation_id VARCHAR(64) NOT NULL COMMENT '所属分配',
 state VARCHAR(24) NOT NULL COMMENT 'READY、LAUNCHING、TCC_TRYING',
 launch_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '启动隔离代际',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
 launch_owner VARCHAR(64) NULL COMMENT '本代启动执行器',
 lease_until DATETIME(6) NULL COMMENT '按数据库时钟计算的启动租约',
 xid VARCHAR(128) NULL COMMENT '唯一绑定TC事务，绑定后禁止替换',
 entry_protocol_version INT NOT NULL COMMENT '受控Try入口证明版本；0表示未证明',
 PRIMARY KEY(enterprise_id,attempt_id),
 UNIQUE KEY uk_xid(xid),
 CONSTRAINT ck_launch_state CHECK(state IN ('READY','LAUNCHING','TCC_TRYING')),
 CONSTRAINT ck_bound_state CHECK((state='TCC_TRYING' AND xid IS NOT NULL) OR (state<>'TCC_TRYING' AND xid IS NULL))
) ENGINE=InnoDB COMMENT='S0启动CAS与恢复隔离探针';
