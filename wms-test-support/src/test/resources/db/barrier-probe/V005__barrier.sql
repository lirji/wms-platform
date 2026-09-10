CREATE TABLE allocation_attempt (
 enterprise_id VARCHAR(32) NOT NULL COMMENT '企业作用域',
 attempt_id VARCHAR(64) NOT NULL COMMENT '不可变尝试标识',
 launch_epoch BIGINT NOT NULL COMMENT '启动隔离代际',
 xid VARCHAR(128) NOT NULL COMMENT '已绑定且不可覆盖的TC事务',
 expected_tm VARCHAR(64) NOT NULL COMMENT '允许的TM应用身份',
 expected_group VARCHAR(32) NOT NULL COMMENT '允许的事务分组',
 participant_set VARCHAR(128) NOT NULL COMMENT '固定参与仓清单',
 PRIMARY KEY (enterprise_id, attempt_id),
 UNIQUE KEY uk_barrier_xid (xid)
) ENGINE=InnoDB COMMENT='履约侧attempt与XID/代际/参与者绑定；不是TC决定表';

CREATE TABLE attempt_participant (
 enterprise_id VARCHAR(32) NOT NULL COMMENT '企业作用域',
 attempt_id VARCHAR(64) NOT NULL COMMENT '所属尝试',
 warehouse_id VARCHAR(32) NOT NULL COMMENT '参与仓',
 fence_status TINYINT NOT NULL COMMENT '本地Fence：1Tried、2Committed、3Rollbacked',
 PRIMARY KEY (enterprise_id, attempt_id, warehouse_id)
) ENGINE=InnoDB COMMENT='固定参与者本地Fence观察；不能替代TC终态';

CREATE TABLE release_outbox (
 outbox_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本地Outbox主键',
 enterprise_id VARCHAR(32) NOT NULL COMMENT '企业作用域',
 attempt_id VARCHAR(64) NOT NULL COMMENT '所属尝试',
 payload VARCHAR(32) NOT NULL COMMENT '只允许ALLOCATED',
 PRIMARY KEY (outbox_id),
 UNIQUE KEY uk_allocated_attempt (enterprise_id, attempt_id)
) ENGINE=InnoDB COMMENT='仅屏障允许后可写ALLOCATED；缺证据不得插入';
