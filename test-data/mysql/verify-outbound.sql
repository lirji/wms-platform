SELECT 'outbound.demo.allocated' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM outbound_order o
JOIN outbound_line l ON l.enterprise_id=o.enterprise_id AND l.warehouse_id=o.warehouse_id AND l.order_id=o.id
WHERE o.enterprise_id='ENT-DEMO' AND o.id='OB-DEMO-ALLOC-A' AND o.status='ALLOCATED'
  AND o.allocation_id='FF-DEMO-PLANNED' AND o.attempt_id='ATT-DEMO-PLANNED'
  AND l.sku_id='SKU-STD' AND l.allocated_qty=6;

SELECT 'outbound.testfl.pending' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM outbound_order
WHERE id='TESTFL-OB-PENDING-A' AND status='PENDING_AUTHORIZATION'
  AND execution_authorization_id IS NULL;
