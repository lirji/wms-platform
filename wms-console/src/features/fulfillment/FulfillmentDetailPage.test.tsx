import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { FulfillmentDetailPage } from "./FulfillmentDetailPage";

describe("FulfillmentDetailPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("maps fulfillment GET fields and does not show inbound physical/sync headers", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      fulfillmentId: "FF-1",
      id: "FF-1",
      status: "OPEN",
      ownerId: "OWNER-SELF",
      sourceSystem: "OMS",
      sourceOrderNo: "SO-1",
      version: 2,
      lines: [{ source_line_id: "SL-1", sku_id: "SKU-STD", requested_qty: "3", base_unit: "EA" }],
      participants: [],
      cancellations: []
    }), { status: 200 })));
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/fulfillment/FF-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["fulfillment.execute"] }}>
            <Routes>
              <Route path="/w/:warehouseId/fulfillment/:fulfillmentId" element={<FulfillmentDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    await waitFor(() => expect(screen.getByText("SO-1")).toBeTruthy());
    expect(screen.getByText("来源单号")).toBeTruthy();
    expect(screen.getByText("SKU-STD")).toBeTruthy();
    expect(screen.getAllByText("请求数量").length).toBeGreaterThan(0);
    expect(screen.queryByText("库存同步")).toBeNull();
    expect(screen.queryByText(/^实物$/)).toBeNull();
  });

  it("exposes cancel request without promising ALLOCATED", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/fulfillment/FF-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["fulfillment.execute", "fulfillment.cancel"] }}>
            <Routes>
              <Route path="/w/:warehouseId/fulfillment/:fulfillmentId" element={<FulfillmentDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "准备分配" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^执行跨仓分配$/ }));
    expect(screen.getByRole("button", { name: "提交执行" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^请求取消履约$/ }));
    expect(screen.getByRole("button", { name: "请求取消" })).toBeTruthy();
    expect(screen.getByText(/不会写成 ALLOCATED/)).toBeTruthy();
  });
});
