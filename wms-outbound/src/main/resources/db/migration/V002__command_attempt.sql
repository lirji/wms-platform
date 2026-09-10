-- S2-07：来源命令补尝试/安全关闭字段。唯一键已是 effect+action+attempt_no。

ALTER TABLE source_command
  ADD COLUMN previous_command_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '安全关闭所依据的上一命令，首次可空' AFTER attempt_no,
  ADD COLUMN safe_close_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '安全关闭证据标识，未关闭可空',
  ADD COLUMN safe_close_version BIGINT NOT NULL DEFAULT 0 COMMENT '安全关闭证据版本',
  ADD COLUMN safe_close_evidence JSON NULL COMMENT '安全关闭证据正文，未关闭可空';
