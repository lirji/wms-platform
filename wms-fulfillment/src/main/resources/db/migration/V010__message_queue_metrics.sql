-- 状态加创建时间索引支持最旧积压定位，避免监控全表扫描。
CREATE INDEX idx_runtime_inbox_age ON runtime_message_inbox (status, created_at);
