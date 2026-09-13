-- TESTFL 出库补充场景。列与 OutboundOrderMapper.insertOrderIgnore / insertLineIgnore 一致。
-- PENDING_AUTHORIZATION 是 OutboundOrderService 建单后的真实初始状态。
-- 不写 ALLOCATED+TCC 证据。可重复：ON DUPLICATE KEY UPDATE。

INSERT INTO outbound_order (
  id, enterprise_id, warehouse_id, allocation_id, attempt_id, owner_id,
  execution_authorization_id, status, version, created_at, updated_at
) VALUES (
  'TESTFL-OB-PENDING-A', 'ENT-DEMO', 'WH-A', 'TESTFL-FF-PENDING', 'TESTFL-ATT-PENDING', 'OWNER-SELF',
  NULL, 'PENDING_AUTHORIZATION', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
) ON DUPLICATE KEY UPDATE id=id;

INSERT INTO outbound_line (
  id, enterprise_id, warehouse_id, order_id, order_line_id, sku_id, allocated_qty,
  picked_physical_qty, picked_posted_qty, packed_physical_qty, packed_posted_qty,
  shipped_physical_qty, shipped_posted_qty, cancelled_qty, base_unit, stock_sync_status,
  version, created_at, updated_at
) VALUES (
  'TESTFL-OB-PENDING-A-L1', 'ENT-DEMO', 'WH-A', 'TESTFL-OB-PENDING-A', 'L1', 'SKU-STD', 3,
  0, 0, 0, 0, 0, 0, 0, 'EA', 'PENDING', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
) ON DUPLICATE KEY UPDATE id=id;
