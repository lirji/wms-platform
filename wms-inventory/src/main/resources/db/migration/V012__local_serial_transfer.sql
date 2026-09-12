-- S6-02 仓内序列号转移观察。源仓 SEALED 后旧授权不得恢复可用。

ALTER TABLE local_serial
  ADD COLUMN transfer_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '当前或最近转移',
  ADD COLUMN source_release_ref VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '源仓释放引用，未封闭可空';
