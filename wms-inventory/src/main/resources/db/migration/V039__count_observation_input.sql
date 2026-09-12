-- 旧观察没有完整输入证明，保持NULL，不根据当前身份或历史子行猜测回填。
ALTER TABLE count_observation
  ADD COLUMN observation_kind VARCHAR(16) COLLATE utf8mb4_bin NULL COMMENT 'QUANTITY或SERIAL；NULL表示旧记录缺少输入证明',
  ADD COLUMN serial_input_json JSON NULL COMMENT 'V1规范化完整实见清单；空数组代表全部未见',
  ADD CONSTRAINT ck_count_observation_input CHECK ((observation_kind IS NULL AND serial_input_json IS NULL) OR
    (observation_kind='QUANTITY' AND serial_input_json IS NULL) OR (observation_kind='SERIAL' AND serial_input_json IS NOT NULL));
