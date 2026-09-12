export const DESKTOP_NAV: Array<{ to: string; label: string; end?: boolean }> = [
  { to: "", label: "工作台", end: true },
  { to: "catalog", label: "商品/库位" },
  { to: "inbound", label: "入库" },
  { to: "stock", label: "库存台账" },
  { to: "fulfillment", label: "出库履约" },
  { to: "transfers", label: "调拨" },
  { to: "counts", label: "盘点" },
  { to: "jobs", label: "任务/设备" },
  { to: "recon", label: "对账" }
];
