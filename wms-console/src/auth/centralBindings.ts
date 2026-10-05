// 由Owner真实操作绑定生成；只声明能力语义，不包含成员或授权数据。
export const CENTRAL_BINDINGS: Record<string, string> = {
  "adjustment.apply:wms_warehouse": "wms.adjustment.apply",
  "adjustment.approve:wms_warehouse": "wms.adjustment.approve",
  "adjustment.create:wms_warehouse": "wms.adjustment.create",
  "adjustment.read:wms_warehouse": "wms.adjustment.read",
  "count.create:wms_warehouse": "wms.count.create",
  "count.freeze:wms_warehouse": "wms.count.freeze",
  "count.read:wms_warehouse": "wms.count.read",
  "count.record:wms_warehouse": "wms.count.record",
  "fulfillment.cancel:wms_enterprise": "wms.fulfillment.cancel",
  "fulfillment.create:wms_enterprise": "wms.fulfillment.create",
  "fulfillment.execute:wms_enterprise": "wms.fulfillment.execute.enterprise",
  "fulfillment.execute:wms_warehouse": "wms.fulfillment.execute",
  "fulfillment.read:wms_enterprise": "wms.fulfillment.read",
  "inbound.create:wms_warehouse": "wms.inbound.create",
  "inbound.putaway:wms_warehouse": "wms.inbound.putaway",
  "inbound.read:wms_warehouse": "wms.inbound.read",
  "inbound.receive:wms_warehouse": "wms.inbound.receive",
  "inventory.read:wms_warehouse": "wms.inventory.read",
  "job.read:wms_warehouse": "wms.job.read",
  "job.retry:wms_warehouse": "wms.job.retry",
  "masterdata.read:wms_enterprise": "wms.masterdata.read.enterprise",
  "masterdata.read:wms_warehouse": "wms.masterdata.read",
  "masterdata.write:wms_enterprise": "wms.masterdata.write.enterprise",
  "masterdata.write:wms_warehouse": "wms.masterdata.write",
  "messaging.read:wms_warehouse": "wms.messaging.read",
  "messaging.recover:wms_warehouse": "wms.messaging.recover",
  "operation.read:wms_warehouse": "wms.operation.read",
  "outbound.pack:wms_warehouse": "wms.outbound.pack",
  "outbound.pick:wms_warehouse": "wms.outbound.pick",
  "outbound.read:wms_warehouse": "wms.outbound.read",
  "outbound.ship:wms_warehouse": "wms.outbound.ship",
  "quality.inspect:wms_warehouse": "wms.quality.inspect",
  "recon.export:wms_enterprise": "wms.recon.export.enterprise",
  "recon.export:wms_warehouse": "wms.recon.export",
  "recon.read:wms_enterprise": "wms.recon.read.enterprise",
  "recon.read:wms_warehouse": "wms.recon.read",
  "recon.remediate:wms_enterprise": "wms.recon.remediate.enterprise",
  "recon.remediate:wms_warehouse": "wms.recon.remediate",
  "stock.audit:wms_warehouse": "wms.stock.audit",
  "stock.hold:wms_warehouse": "wms.stock.hold",
  "stock.move:wms_warehouse": "wms.stock.move",
  "stock.read:wms_warehouse": "wms.stock.read",
  "stock.releaseHold:wms_warehouse": "wms.stock.release_hold",
  "task.claim:wms_warehouse": "wms.task.claim",
  "task.read:wms_warehouse": "wms.task.read",
  "transfer.authorizeReceipt:wms_warehouse": "wms.transfer.authorize_receipt",
  "transfer.create:wms_warehouse": "wms.transfer.create",
  "transfer.read:wms_warehouse": "wms.transfer.read",
  "transfer.receive:wms_warehouse": "wms.transfer.receive"
};
export const CENTRAL_QUERIES = [
  {
    "path": "/api/wms/v1/fulfillments",
    "capability": "wms.fulfillment.read"
  },
  {
    "path": "/api/wms/v1/fulfillments/{fulfillmentId}",
    "capability": "wms.fulfillment.read"
  },
  {
    "path": "/api/wms/v1/inventory",
    "capability": "wms.stock.read"
  },
  {
    "path": "/api/wms/v1/jobs",
    "capability": "wms.job.read"
  },
  {
    "path": "/api/wms/v1/jobs/{jobId}",
    "capability": "wms.job.read"
  },
  {
    "path": "/api/wms/v1/operations/{operationId}",
    "capability": "wms.operation.read"
  },
  {
    "path": "/api/wms/v1/reconciliation-cases",
    "capability": "wms.recon.read.enterprise"
  },
  {
    "path": "/api/wms/v1/reconciliation-snapshots/{snapshotId}",
    "capability": "wms.recon.read.enterprise"
  },
  {
    "path": "/api/wms/v1/skus",
    "capability": "wms.masterdata.read.enterprise"
  },
  {
    "path": "/api/wms/v1/skus/{skuId}",
    "capability": "wms.masterdata.read.enterprise"
  },
  {
    "path": "/api/wms/v1/skus/{skuId}/units",
    "capability": "wms.masterdata.read.enterprise"
  },
  {
    "path": "/api/wms/v1/transfers",
    "capability": "wms.transfer.read"
  },
  {
    "path": "/api/wms/v1/transfers/{transferId}",
    "capability": "wms.transfer.read"
  },
  {
    "path": "/api/wms/v1/transfers/{transferId}/serial-commands/{commandId}",
    "capability": "wms.transfer.read"
  },
  {
    "path": "/api/wms/v1/warehouses",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/action-effects",
    "capability": "wms.task.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/action-effects/{effectId}",
    "capability": "wms.task.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/adjustments",
    "capability": "wms.adjustment.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/adjustments/{adjustmentId}",
    "capability": "wms.adjustment.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/count-plans",
    "capability": "wms.count.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/count-plans/{countPlanId}",
    "capability": "wms.count.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/inbound-orders",
    "capability": "wms.inbound.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/inbound-orders/{inboundOrderId}",
    "capability": "wms.inbound.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/inbound-orders/{inboundOrderId}/receipts",
    "capability": "wms.inbound.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/inventory/{balanceId}/ledger",
    "capability": "wms.stock.audit"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/locations",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/locations/{locationId}",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/locations/{locationId}/gate",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/lots",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/lots/{lotId}",
    "capability": "wms.masterdata.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/message-queues/{queue}/messages",
    "capability": "wms.messaging.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/outbound-orders",
    "capability": "wms.outbound.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}",
    "capability": "wms.outbound.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/outbound-orders/{outboundOrderId}/shippable-serials",
    "capability": "wms.outbound.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/reconciliation-cases",
    "capability": "wms.recon.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/reconciliation-cases/{caseId}",
    "capability": "wms.recon.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/reconciliation-windows/{cutoffId}",
    "capability": "wms.recon.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/serial-recoveries",
    "capability": "wms.messaging.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/serial-stock",
    "capability": "wms.inventory.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/tasks",
    "capability": "wms.task.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}",
    "capability": "wms.task.read"
  },
  {
    "path": "/api/wms/v1/warehouses/{warehouseId}/tasks/{taskId}/action-effects",
    "capability": "wms.task.read"
  }
];
