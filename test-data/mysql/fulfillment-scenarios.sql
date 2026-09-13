-- TESTFL 履约补充场景。列与 SeedFulfillmentMapper 一致。
-- request_digest / participant_set_hash / allocation_digest 按 SeedFulfillment 与
-- FulfillmentService.participantHash / AllocationPlan.digest 的算法在库内计算。
-- deadline 已过期：AllocationExecutionWorker 不会启动该 attempt。

INSERT IGNORE INTO fulfillment_order (
  id, enterprise_id, source_system, source_order_no, request_digest, status,
  strategy_version, owner_id, version, created_at, updated_at
) VALUES (
  'TESTFL-FF-EXPIRED',
  'ENT-DEMO',
  'OMS',
  'SO-TESTFL-EXPIRED',
  SHA2('ENT-DEMO|OMS|SO-TESTFL-EXPIRED|SKU-STD|4|EA', 256),
  'OPEN',
  0,
  'OWNER-SELF',
  0,
  UTC_TIMESTAMP(6),
  UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO fulfillment_line (
  id, enterprise_id, fulfillment_id, source_line_id, sku_id, requested_qty, base_unit,
  min_remaining_days, version, created_at, updated_at
) VALUES (
  'TESTFL-FF-EXPIRED-L1', 'ENT-DEMO', 'TESTFL-FF-EXPIRED', 'L1', 'SKU-STD', 4, 'EA',
  0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO allocation_attempt (
  id, enterprise_id, fulfillment_id, state, deadline, participant_set_hash, allocation_digest,
  cancel_requested, launch_epoch, version, created_at, updated_at
) VALUES (
  'TESTFL-ATT-EXPIRED',
  'ENT-DEMO',
  'TESTFL-FF-EXPIRED',
  'PLANNED',
  DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR),
  SHA2('WH-A', 256),
  SHA2(CONCAT('alloc-v1', CHAR(31), 'WH-A', CHAR(31), 'L1', CHAR(31), 'SKU-STD', CHAR(31), '4', CHAR(31), 'EA'), 256),
  0,
  0,
  0,
  UTC_TIMESTAMP(6),
  UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO allocation_participant (
  id, enterprise_id, attempt_id, warehouse_id, state, version, created_at, updated_at
) VALUES (
  'TESTFL-PAR-EXPIRED-A', 'ENT-DEMO', 'TESTFL-ATT-EXPIRED', 'WH-A', 'PLANNED',
  0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO participant_line (
  id, enterprise_id, participant_id, order_line_id, sku_id, qty, base_unit, version, created_at, updated_at
) VALUES (
  'TESTFL-PARL-EXPIRED-A-L1', 'ENT-DEMO', 'TESTFL-PAR-EXPIRED-A', 'L1', 'SKU-STD', 4, 'EA',
  0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

UPDATE fulfillment_order
SET active_attempt_id='TESTFL-ATT-EXPIRED', updated_at=UTC_TIMESTAMP(6)
WHERE enterprise_id='ENT-DEMO'
  AND id='TESTFL-FF-EXPIRED'
  AND (active_attempt_id IS NULL OR active_attempt_id='TESTFL-ATT-EXPIRED');
