-- 旧CLEANED不伪造TC证明；新清理必须保留只读TC观察的原始证据。
ALTER TABLE allocation_launch ADD COLUMN cleanup_terminal_evidence JSON NULL COMMENT '已知空XID清理的原TC回滚终态证据；历史NULL表示缺失';
