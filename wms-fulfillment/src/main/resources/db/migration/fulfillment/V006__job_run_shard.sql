-- S7-02 履约库任务身份与分片。只写本库。XXL 触发不等于分片完成。

CREATE TABLE job_run (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '任务运行标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  run_key VARCHAR(256) COLLATE utf8mb4_bin NOT NULL COMMENT 'enterprise/jobType/scope/window/inputVersion',
  job_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '目录任务类型',
  scope_code VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定业务范围',
  window_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '窗口身份',
  input_version VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '规划输入版本',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'PLANNING/PLANNED/RUNNING/SUCCEEDED/PARTIAL_FAILED/FAILED',
  planned_shards INT NOT NULL DEFAULT 0 COMMENT '已规划分片数',
  succeeded_shards INT NOT NULL DEFAULT 0 COMMENT '已成功分片数',
  failed_shards INT NOT NULL DEFAULT 0 COMMENT '已失败分片数',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_run (enterprise_id, warehouse_id, run_key),
  CONSTRAINT ck_job_run_counts CHECK (planned_shards >= 0 AND succeeded_shards >= 0 AND failed_shards >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约任务运行，调度成功不等于本行完成';

CREATE TABLE job_shard (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '分片标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '仓库及路由键',
  run_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '所属运行',
  job_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '目录任务类型',
  shard_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定仓/桶/区间，不用执行器取模',
  state VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'READY/LEASED/SUCCEEDED/FAILED/QUARANTINED/CANCELLED',
  lease_owner VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前租约持有者，未领取可空',
  lease_until DATETIME(6) NULL COMMENT '租约到期，未领取可空',
  claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '领取代际，旧 epoch 提交必须失败',
  fence_token VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '本次领取栅栏，未领取可空',
  cursor_key VARCHAR(128) COLLATE utf8mb4_bin NULL COMMENT '稳定游标，未开始可空',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_retry_at DATETIME(6) NULL COMMENT '下次可领，立即可空',
  last_error VARCHAR(255) COLLATE utf8mb4_bin NULL COMMENT '最近错误，无错误可空',
  live_guard VARCHAR(64) COLLATE utf8mb4_bin GENERATED ALWAYS AS (CASE WHEN state IN ('SUCCEEDED','FAILED','QUARANTINED','CANCELLED') THEN id ELSE 'LIVE' END) STORED COMMENT '活跃分片收敛键，终态放开',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_shard (enterprise_id, warehouse_id, run_id, shard_key),
  UNIQUE KEY uk_job_shard_active (enterprise_id, warehouse_id, job_type, shard_key, live_guard),
  KEY idx_job_shard_claim (enterprise_id, warehouse_id, job_type, state, next_retry_at, lease_until),
  CONSTRAINT ck_job_shard_epoch CHECK (claim_epoch >= 0 AND retry_count >= 0 AND version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约分片租约与检查点，回收后旧 fence 失效';
