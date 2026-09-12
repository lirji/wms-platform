import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { InboundDetailPage } from "./InboundDetailPage";

describe("InboundDetailPage", () => {
  it("exposes receive, quality and putaway commands", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/inbound/ASN-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["inbound.receive", "quality.inspect", "inbound.putaway"] }}>
            <Routes>
              <Route path="/w/:warehouseId/inbound/:inboundOrderId" element={<InboundDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "入库单 ASN-1" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "提交收货" })).toBeTruthy();
    expect(screen.getByText("序列号观察")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "质检" }));
    expect(screen.getByRole("button", { name: "记录质检" })).toBeTruthy();
    expect(screen.getByText("累计合格身份")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "上架" }));
    expect(screen.getByRole("button", { name: "提交上架" })).toBeTruthy();
    expect(screen.getByText("上架身份")).toBeTruthy();
  });

  it("hides inbound commands when the token has no job scopes", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/inbound/ASN-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <Routes>
              <Route path="/w/:warehouseId/inbound/:inboundOrderId" element={<InboundDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.queryByRole("button", { name: "提交命令" })).toBeNull();
    expect(screen.queryByRole("button", { name: "提交收货" })).toBeNull();
  });
});
