import type { ReactNode } from "react";
import {
  AppstoreOutlined,
  AuditOutlined,
  ClusterOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  ExceptionOutlined,
  ExportOutlined,
  ImportOutlined,
  MobileOutlined,
  SwapOutlined
} from "@ant-design/icons";

export type NavItem = {
  to: string;
  label: string;
  icon: ReactNode;
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
    items: [{ to: "", label: "作业总览", end: true, icon: <DashboardOutlined /> }]
  },
  {
    title: "入出存",
    items: [
      { to: "catalog", label: "商品 / 库位", icon: <AppstoreOutlined /> },
      { to: "inbound", label: "入库作业", icon: <ImportOutlined /> },
      { to: "stock", label: "库存台账", icon: <DatabaseOutlined /> },
      { to: "fulfillment", label: "出库履约", icon: <ExportOutlined /> }
    ]
  },
  {
    title: "仓内协同",
    items: [
      { to: "transfers", label: "调拨", icon: <SwapOutlined /> },
      { to: "counts", label: "盘点", icon: <AuditOutlined /> },
      { to: "receive", label: "PDA 收货", pda: true, icon: <MobileOutlined /> },
      { to: "pick", label: "PDA 拣货", pda: true, icon: <MobileOutlined /> },
      { to: "ship", label: "PDA 发运", pda: true, icon: <MobileOutlined /> }
    ]
  },
  {
    title: "管控",
    items: [
      { to: "jobs", label: "任务 / 设备", icon: <ClusterOutlined /> },
      { to: "recon", label: "对账差异", icon: <ExceptionOutlined /> }
    ]
  }
];
