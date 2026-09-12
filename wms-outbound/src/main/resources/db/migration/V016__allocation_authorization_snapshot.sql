-- 原始成功屏障正文与本地证据同事务入库；旧手工证据不得冒充完整消息授权。
ALTER TABLE outbound_tcc_evidence ADD COLUMN barrier_payload JSON NULL COMMENT '履约V1完整成功屏障快照，用于同attempt重复内容校验';
