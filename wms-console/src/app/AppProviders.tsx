import { ConfigProvider, App as AntApp } from "antd";
import zhCN from "antd/locale/zh_CN";
import type { ReactNode } from "react";
import { wmsTheme } from "../design/theme";

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider locale={zhCN} theme={wmsTheme} wave={{ disabled: true }} button={{ autoInsertSpace: false }}>
      <AntApp>{children}</AntApp>
    </ConfigProvider>
  );
}
