-- S4-02 冻结选仓数量摘要。不改 TC 观察字段，不发明单位/效期默认。

ALTER TABLE allocation_attempt
  ADD COLUMN allocation_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL
    COMMENT '固定参与仓+行数量SHA-256十六进制'
    AFTER participant_set_hash;
