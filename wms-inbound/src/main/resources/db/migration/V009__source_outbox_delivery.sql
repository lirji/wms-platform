-- 来源发布领取和业务T1分开；网络确认后以相同代际完成，旧worker不得覆盖恢复者。
ALTER TABLE source_outbox
  ADD COLUMN claim_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '发布领取代际，单调递增',
  ADD COLUMN lease_until DATETIME(6) NULL COMMENT 'UTC领取期限，不代表撤销已投递事件',
  ADD COLUMN published_at DATETIME(6) NULL COMMENT 'UTC发布确认落库时刻',
  ADD COLUMN error_code VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '脱敏重试或隔离原因';
