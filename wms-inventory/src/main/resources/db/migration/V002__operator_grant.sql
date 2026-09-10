-- 测试身份与仓权限映射，仅种子写入；运行时仍以 JWT 仓范围为准。

CREATE TABLE operator_grant (
  id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '授权映射标识',
  enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业权限范围',
  subject VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT 'Casdoor用户名或sub提示',
  warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '可访问仓库',
  permission_code VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '权限稳定编码',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(6) NOT NULL COMMENT 'UTC创建时刻',
  updated_at DATETIME(6) NOT NULL COMMENT 'UTC最后变更时刻',
  PRIMARY KEY (id),
  UNIQUE KEY uk_operator_grant (enterprise_id, subject, warehouse_id, permission_code),
  CONSTRAINT ck_operator_grant_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='种子权限账户映射，不能代替令牌仓范围校验';
