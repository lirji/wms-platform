import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { ReconPage } from "./ReconPage";

describe("ReconPage", () => {
  it("exposes window request and snapshot read without inventing watermarks", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/recon?cutoffId=C-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["recon.read", "recon.export", "recon.remediate"] }}>
            <ReconPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("button", { name: "请求窗口" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "重排窗口" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "取消窗口" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "读取快照" })).toBeTruthy();
    expect(screen.getByText(/只有 COMPLETE 才返回服务器三方水位/)).toBeTruthy();
  });
});
