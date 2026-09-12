-- 对外命令键按企业/仓隔离，技术主键不再强制与命令键相同。
-- 既有主键不回填，旧应用仍按command_id查询，允许滚动升级期间新旧写法共存。
ALTER TABLE source_command DROP CHECK ck_source_command_id;
ALTER TABLE source_command MODIFY COLUMN id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL
    COMMENT '内部技术标识，幂等由企业/仓/command_id唯一约束承担';
