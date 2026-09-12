export type NavItem = {
  to: string;
  label: string;
  end?: boolean;
  pda?: boolean;
};

export type NavGroup = {
  title: string;
  items: NavItem[];
};

export const NAV_GROUPS: NavGroup[] = [
  {
    title: "总览",
    items: [{ to: "", label: "作业总览", end: true }]
  },
  {
    title: "入出存",
    items: [
      { to: "catalog", label: "商品 / 库位" },
      { to: "inbound", label: "入库作业" },
      { to: "stock", label: "库存台账" },
      { to: "fulfillment", label: "出库履约" }
    ]
  },
  {
    title: "仓内协同",
    items: [
      { to: "transfers", label: "调拨" },
      { to: "counts", label: "盘点" },
      { to: "receive", label: "PDA 收货", pda: true }
    ]
  },
  {
    title: "管控",
    items: [
      { to: "jobs", label: "任务 / 设备" },
      { to: "recon", label: "对账差异" }
    ]
  }
];
