-- TESTFL 入库补充场景。列与 SeedInboundMapper.insertOrderIgnore / insertLineIgnore 一致。
-- 可重复：INSERT IGNORE。不删除非 TESTFL 数据。

INSERT IGNORE INTO inbound_order (
  id, enterprise_id, warehouse_id, external_source, external_no, owner_id, status,
  expected_at, source_version, version, created_at, updated_at
) VALUES (
  'TESTFL-INB-SN-A', 'ENT-DEMO', 'WH-A', 'TESTFL', 'TESTFL-ASN-SN-A', 'OWNER-SELF', 'APPROVED',
  NULL, 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO inbound_line (
  id, enterprise_id, warehouse_id, order_id, external_line_id, sku_id,
  expected_qty, received_physical_qty, received_posted_qty, putaway_physical_qty, putaway_posted_qty,
  closed_qty, base_unit, stock_sync_status, version, created_at, updated_at
) VALUES (
  'TESTFL-INB-SN-A-L1', 'ENT-DEMO', 'WH-A', 'TESTFL-INB-SN-A', 'L1', 'SKU-SN',
  2, 0, 0, 0, 0, 0, 'EA', 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO inbound_order (
  id, enterprise_id, warehouse_id, external_source, external_no, owner_id, status,
  expected_at, source_version, version, created_at, updated_at
) VALUES (
  'TESTFL-INB-LATE-A', 'ENT-DEMO', 'WH-A', 'TESTFL', 'TESTFL-ASN-LATE-A', 'OWNER-SELF', 'APPROVED',
  DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY), 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);

INSERT IGNORE INTO inbound_line (
  id, enterprise_id, warehouse_id, order_id, external_line_id, sku_id,
  expected_qty, received_physical_qty, received_posted_qty, putaway_physical_qty, putaway_posted_qty,
  closed_qty, base_unit, stock_sync_status, version, created_at, updated_at
) VALUES (
  'TESTFL-INB-LATE-A-L1', 'ENT-DEMO', 'WH-A', 'TESTFL-INB-LATE-A', 'L1', 'SKU-NEAR',
  4, 0, 0, 0, 0, 0, 'EA', 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
);
