SELECT 'fulfillment.demo.open' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM fulfillment_order
WHERE enterprise_id='ENT-DEMO' AND id='FF-DEMO-OPEN' AND status='OPEN';

SELECT 'fulfillment.demo.planned' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM fulfillment_order o
JOIN allocation_attempt a ON a.enterprise_id=o.enterprise_id AND a.id=o.active_attempt_id
WHERE o.id='FF-DEMO-PLANNED' AND o.status='OPEN' AND a.state='PLANNED' AND a.deadline > UTC_TIMESTAMP(6);

SELECT 'fulfillment.demo.transfer' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM transfer_order
WHERE enterprise_id='ENT-DEMO' AND id='TR-DEMO-AB' AND status='OPEN'
  AND source_warehouse_id='WH-A' AND target_warehouse_id='WH-B';

SELECT 'fulfillment.testfl.expired' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM fulfillment_order o
JOIN allocation_attempt a ON a.enterprise_id=o.enterprise_id AND a.id=o.active_attempt_id
WHERE o.id='TESTFL-FF-EXPIRED' AND o.status='OPEN' AND a.id='TESTFL-ATT-EXPIRED'
  AND a.state='PLANNED' AND a.deadline < UTC_TIMESTAMP(6);
