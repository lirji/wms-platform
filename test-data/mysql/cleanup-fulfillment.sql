-- 仅删除 TESTFL 前缀履约数据。先清依赖行。

UPDATE fulfillment_order
SET active_attempt_id=NULL, updated_at=UTC_TIMESTAMP(6)
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM participant_line
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM allocation_participant
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM allocation_attempt
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM fulfillment_line
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';

DELETE FROM fulfillment_order
WHERE enterprise_id='ENT-DEMO'
  AND id LIKE 'TESTFL-%';
