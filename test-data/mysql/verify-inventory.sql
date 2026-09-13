SELECT 'inventory.warehouse.a' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM warehouse
WHERE enterprise_id='ENT-DEMO' AND id='WH-A' AND state='ACTIVE';

SELECT 'inventory.sku.std' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM sku
WHERE enterprise_id='ENT-DEMO' AND id='SKU-STD' AND state='ACTIVE'
  AND lot_enabled=0 AND serial_enabled=0;

SELECT 'inventory.sku.sn.no-opening-stock' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM sku s
LEFT JOIN stock_balance b ON b.enterprise_id=s.enterprise_id AND b.sku_id=s.id AND b.warehouse_id='WH-A'
WHERE s.id='SKU-SN' AND s.serial_enabled=1 AND b.id IS NULL;

SELECT 'inventory.stock.std.good' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM stock_balance
WHERE id='BAL-WH-A-STD-STO-GOOD' AND sku_id='SKU-STD' AND quality_code='GOOD'
  AND location_id='WH-A-STO' AND on_hand_qty=120;

SELECT 'inventory.stock.expired.hold' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM stock_balance
WHERE id='BAL-WH-A-EXP-STO-HOLD' AND sku_id='SKU-EXPIRED' AND quality_code='HOLD' AND on_hand_qty=6;

SELECT 'inventory.count.draft' AS check_id,
       CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result
FROM count_plan
WHERE enterprise_id='ENT-DEMO' AND id='CNT-DEMO-DRAFT-WH-A' AND status='DRAFT';
