SELECT 'inbound.demo.open' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM inbound_order
WHERE enterprise_id='ENT-DEMO' AND warehouse_id='WH-A' AND id='INB-DEMO-OPEN-A' AND status='APPROVED';

SELECT 'inbound.demo.receiving' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM inbound_order o
JOIN inbound_line l ON l.enterprise_id=o.enterprise_id AND l.warehouse_id=o.warehouse_id AND l.order_id=o.id
WHERE o.enterprise_id='ENT-DEMO' AND o.id='INB-DEMO-RCV-A' AND o.status='RECEIVING'
  AND l.sku_id='SKU-STD' AND l.expected_qty=20 AND l.received_physical_qty=8;

SELECT 'inbound.testfl.serial' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM inbound_order o
JOIN inbound_line l ON l.order_id=o.id AND l.enterprise_id=o.enterprise_id
WHERE o.id='TESTFL-INB-SN-A' AND o.status='APPROVED' AND l.sku_id='SKU-SN' AND l.expected_qty=2;

SELECT 'inbound.testfl.late' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM inbound_order
WHERE id='TESTFL-INB-LATE-A' AND status='APPROVED' AND expected_at < UTC_TIMESTAMP(6);
