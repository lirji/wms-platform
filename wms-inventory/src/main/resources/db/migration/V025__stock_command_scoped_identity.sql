-- 外部命令只在来源服务/企业/仓内唯一；技术主键不能施加意外的全库唯一范围。
ALTER TABLE stock_command DROP CHECK ck_stock_command_id;
ALTER TABLE stock_command MODIFY COLUMN id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '技术主键，外部命令按来源与企业仓唯一';
