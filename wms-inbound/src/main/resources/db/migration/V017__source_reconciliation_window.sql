CREATE TABLE source_window_guard (
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '来源仓范围',
 closed_before DATETIME(6) NULL COMMENT '禁止补插来源命令的UTC排他上界，未关窗时为空',
 PRIMARY KEY(enterprise_id,warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='升级后T1与关窗共用范围锁，旧写节点退出后才可启用关窗';

CREATE TABLE source_reconciliation_window (
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '证明企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '证明仓范围',
 cutoff_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '调用方稳定窗口身份，不能替换原时刻',
 closed_at DATETIME(6) NOT NULL COMMENT '来源命令关闭UTC排他上界',
 last_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '已核对最后命令的稳定主键',
 fact_count BIGINT NOT NULL DEFAULT 0 COMMENT '已核对原物理事实与库存回执数量',
 digest CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '包含范围与有序事实的链式SHA256',
 state VARCHAR(16) COLLATE utf8mb4_bin NOT NULL DEFAULT 'COLLECTING' COMMENT 'COLLECTING或COMPLETE，缺回执不完成',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '分页条件更新版本',
 PRIMARY KEY(enterprise_id,warehouse_id,cutoff_id),
 CONSTRAINT ck_source_window_state CHECK(state IN ('COLLECTING','COMPLETE') AND fact_count>=0 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='来源权威关窗证明与有界采集检查点';
