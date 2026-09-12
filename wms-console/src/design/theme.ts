import type { ThemeConfig } from "antd";

export const wmsTheme: ThemeConfig = {
  token: {
    colorPrimary: "#0f766e",
    colorInfo: "#0f766e",
    colorSuccess: "#15803d",
    colorWarning: "#c2410c",
    colorError: "#b91c1c",
    colorText: "#0f172a",
    colorTextSecondary: "#475569",
    colorBorder: "#e2e8f0",
    colorBgLayout: "#f1f5f9",
    colorBgContainer: "#ffffff",
    borderRadius: 8,
    fontFamily: '"PingFang SC", "Noto Sans SC", "Microsoft YaHei", "Segoe UI", sans-serif',
    fontSize: 13,
    controlHeight: 32,
    boxShadowSecondary: "0 10px 30px -18px rgba(15, 23, 42, 0.28)"
  },
  components: {
    Layout: {
      siderBg: "#07111f",
      headerBg: "rgba(255,255,255,0.86)",
      headerHeight: 56,
      headerPadding: "0 20px",
      bodyBg: "#f1f5f9"
    },
    Menu: {
      darkItemBg: "#07111f",
      darkSubMenuItemBg: "#07111f",
      darkItemSelectedBg: "#0f766e",
      darkItemHoverBg: "rgba(255,255,255,0.06)",
      darkGroupTitleColor: "#64748b",
      itemBorderRadius: 8,
      itemMarginInline: 8
    },
    Table: {
      headerBg: "#f8fafc",
      headerColor: "#64748b",
      rowHoverBg: "#f0fdfa"
    },
    Card: {
      borderRadiusLG: 14
    },
    Button: {
      primaryShadow: "0 8px 18px -10px rgba(15, 118, 110, 0.7)"
    }
  }
};
