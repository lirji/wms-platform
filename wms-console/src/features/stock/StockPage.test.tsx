import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { StockPage } from "./StockPage";

describe("StockPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("maps inventory_view snake_case columns", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      items: [{
        id: "BAL-1",
        sku_id: "SKU-STD",
        lot_id: "-",
        on_hand_qty: "120",
        reserved_qty: "0",
        free_execution_claim_qty: "0",
        quality_code: "GOOD"
      }],
      asOf: "2026-09-13T00:00:00Z",
      lagSeconds: 0
    }), { status: 200 })));
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <StockPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    await waitFor(() => expect(screen.getByText("SKU-STD")).toBeTruthy());
    expect(screen.getByText("120")).toBeTruthy();
    expect(screen.getByText("GOOD")).toBeTruthy();
    expect(screen.getAllByText("执行占用").length).toBeGreaterThan(0);
  });

  it("shows stock domain commands when the token has scopes", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{
            token: "t",
            warehouseId: "WH-A",
            scopes: ["stock.move", "stock.hold", "stock.releaseHold", "adjustment.create", "adjustment.approve", "adjustment.apply"]
          }}>
            <StockPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "库存台账" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "同仓移库" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "库存限制" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "独立调整" })).toBeTruthy();
  });

  it("hides stock commands without scopes", () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <StockPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.queryByRole("button", { name: "同仓移库" })).toBeNull();
    expect(screen.queryByRole("button", { name: "库存限制" })).toBeNull();
  });
});
