import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { TransferDetailPage } from "./TransferDetailPage";

describe("TransferDetailPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("maps transfer GET warehouses, version and line qtys without inbound sync headers", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      transferId: "TR-1",
      status: "ISSUED",
      sourceWarehouseId: "WH-A",
      targetWarehouseId: "WH-B",
      version: 3,
      lines: [{
        id: "TL-1",
        sku_id: "SKU-STD",
        planned_qty: "2",
        issued_qty: "2",
        received_qty: "0",
        loss_confirmed_qty: "0"
      }],
      legs: [{ warehouse_id: "WH-A", role: "SOURCE", status: "ISSUED" }]
    }), { status: 200 })));
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/transfers/TR-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["transfer.create"] }}>
            <Routes>
              <Route path="/w/:warehouseId/transfers/:transferId" element={<TransferDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    await waitFor(() => expect(screen.getByText("WH-B")).toBeTruthy());
    expect(screen.getByText("源仓")).toBeTruthy();
    expect(screen.getByText("目的仓")).toBeTruthy();
    expect(screen.getByText("SKU-STD")).toBeTruthy();
    expect(screen.getAllByText("计划").length).toBeGreaterThan(0);
    expect(screen.queryByText("库存同步")).toBeNull();
  });

  it("exposes quantity and serial transfer commands", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/transfers/TR-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["transfer.create", "transfer.receive", "transfer.authorizeReceipt"] }}>
            <Routes>
              <Route path="/w/:warehouseId/transfers/:transferId" element={<TransferDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "确认发出" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^序列号发出$/ }));
    expect(screen.getByRole("button", { name: "发出身份" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^序列号接收$/ }));
    expect(screen.getByRole("button", { name: "接收身份" })).toBeTruthy();
    expect(screen.getByText("序列调拨命令")).toBeTruthy();
  });
});
