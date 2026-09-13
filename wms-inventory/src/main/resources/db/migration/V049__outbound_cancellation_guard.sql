CREATE TABLE outbound_cancellation_guard (
 id CHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '稳定原单门禁标识，供仓迁移分页',
 enterprise_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '企业范围',
 warehouse_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原仓库',
 document_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '原出库单及STARTED仲裁身份',
 cancellation_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '首次业务取消决定，空表示尚可申请执行',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '门禁版本',
 created_at DATETIME(6) NOT NULL COMMENT '首次仲裁UTC时刻',
 updated_at DATETIME(6) NOT NULL COMMENT '最近取消UTC时刻',
 PRIMARY KEY(id),
 UNIQUE KEY uk_cancel_guard_order(enterprise_id,warehouse_id,document_id),
 CONSTRAINT ck_cancel_guard_version CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原单取消与新STARTED共用数据库门禁，未知实物不得释放';

-- 取消只检查原效果的未结案许可，避免每个原桶扫描全仓许可。
CREATE INDEX idx_execution_permit_effect_state ON execution_permit(enterprise_id,warehouse_id,business_effect_key,state);
