import type { ThemeConfig } from "antd";
import { wmsTokens } from "./tokens";

/** 浅色作业台 + 文档规定的蓝/状态色。灰只给文字、线和 Default 边框。 */
export const wmsTheme: ThemeConfig = {
  token: {
    colorPrimary: wmsTokens.colorPrimary,
    colorInfo: wmsTokens.colorPrimary,
    colorSuccess: wmsTokens.colorSuccess,
    colorWarning: wmsTokens.colorWarning,
    colorError: wmsTokens.colorError,
    colorText: wmsTokens.colorText,
    colorTextSecondary: wmsTokens.colorTextSecondary,
    colorTextTertiary: wmsTokens.colorTextTertiary,
    colorTextDisabled: wmsTokens.colorTextDisabled,
    colorBorder: wmsTokens.colorBorder,
    colorBorderSecondary: wmsTokens.colorSplit,
    colorBgLayout: wmsTokens.colorBgLayout,
    colorBgContainer: wmsTokens.colorBgContainer,
    borderRadius: wmsTokens.borderRadius,
    borderRadiusLG: wmsTokens.borderRadiusLG,
    fontFamily: '"PingFang SC", "Noto Sans SC", "Microsoft YaHei", "Segoe UI", sans-serif',
    fontSize: 14,
    controlHeight: wmsTokens.controlHeight,
    boxShadow: "none",
    boxShadowSecondary: "none"
  },
  components: {
    Layout: {
      siderBg: wmsTokens.colorBgContainer,
      headerBg: wmsTokens.colorBgContainer,
      headerHeight: wmsTokens.headerHeight,
      headerPadding: "0 20px",
      bodyBg: wmsTokens.colorBgLayout
    },
    Menu: {
      itemBg: wmsTokens.colorBgContainer,
      subMenuItemBg: wmsTokens.colorBgContainer,
      itemSelectedBg: wmsTokens.colorPrimaryBg,
      itemSelectedColor: wmsTokens.colorPrimary,
      itemHoverBg: wmsTokens.colorSplit,
      itemColor: wmsTokens.colorTextSecondary,
      groupTitleColor: wmsTokens.colorTextTertiary,
      itemBorderRadius: 6,
      itemMarginInline: 8,
      itemHeight: 40
    },
    Table: {
      headerBg: "#FAFAFA",
      headerColor: wmsTokens.colorTextSecondary,
      headerSplitColor: wmsTokens.colorBorder,
      rowHoverBg: "#F5F9FF",
      rowSelectedBg: wmsTokens.colorPrimaryBg,
      borderColor: wmsTokens.colorSplit,
      cellPaddingBlock: 12
    },
    Card: {
      borderRadiusLG: wmsTokens.borderRadiusLG
    },
    Button: {
      primaryShadow: "none",
      defaultShadow: "none",
      fontWeight: 500,
      paddingInline: 16,
      defaultColor: wmsTokens.colorText,
      defaultBg: wmsTokens.colorBgContainer,
      defaultBorderColor: wmsTokens.colorBorderSecondary,
      defaultHoverColor: wmsTokens.colorPrimary,
      defaultHoverBg: wmsTokens.colorBgContainer,
      defaultHoverBorderColor: wmsTokens.colorPrimary,
      defaultActiveColor: wmsTokens.colorPrimaryActive,
      defaultActiveBg: wmsTokens.colorBgContainer,
      defaultActiveBorderColor: wmsTokens.colorPrimaryActive,
      borderColorDisabled: wmsTokens.colorBorderSecondary,
      colorBgContainerDisabled: "#F5F5F5",
      colorTextDisabled: "#BFBFBF"
    },
    Input: {
      hoverBorderColor: wmsTokens.colorPrimary,
      activeBorderColor: wmsTokens.colorPrimary
    },
    Tag: {
      defaultBg: "#FAFAFA",
      defaultColor: "#595959",
      borderRadiusSM: 4
    },
    Modal: {
      headerBg: wmsTokens.colorBgContainer,
      borderRadiusLG: wmsTokens.borderRadiusLG,
      titleFontSize: 16
    },
    Alert: {
      borderRadiusLG: wmsTokens.borderRadiusLG
    },
    Descriptions: {
      labelColor: wmsTokens.colorTextTertiary,
      contentColor: wmsTokens.colorText
    }
  }
};
