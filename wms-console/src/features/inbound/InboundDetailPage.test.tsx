import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { InboundDetailPage } from "./InboundDetailPage";

describe("InboundDetailPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("maps inbound GET header and line fields without a shared physical header", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (String(url).includes("/receipts")) {
        return new Response(JSON.stringify({ items: [] }), { status: 200 });
      }
      return new Response(JSON.stringify({
        orderId: "INB-1",
        status: "APPROVED",
        ownerId: "OWNER-SELF",
        externalSource: "ERP",
        externalNo: "ASN-1",
        version: 1,
        lines: [{
          id: "IL-1",
          sku_id: "SKU-STD",
          expected_qty: "10",
          received_physical_qty: "0",
          putaway_physical_qty: "0",
          stock_sync_status: "IDLE"
        }]
      }), { status: 200 });
    }));
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/inbound/INB-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["inbound.receive"] }}>
            <Routes>
              <Route path="/w/:warehouseId/inbound/:inboundOrderId" element={<InboundDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    await waitFor(() => expect(screen.getByText("OWNER-SELF")).toBeTruthy());
    expect(screen.getByText("货主")).toBeTruthy();
    expect(screen.getByText("外部单号")).toBeTruthy();
    expect(screen.getByText("SKU-STD")).toBeTruthy();
    expect(screen.getByText("10")).toBeTruthy();
    expect(screen.getAllByText("已收实物").length).toBeGreaterThan(0);
    expect(screen.queryByText(/^实物$/)).toBeNull();
  });

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
