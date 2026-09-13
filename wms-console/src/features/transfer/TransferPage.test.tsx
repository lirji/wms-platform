import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { TransferPage } from "./TransferPage";

describe("TransferPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("maps transfer first-line snake_case columns", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      items: [{
        id: "TR-DEMO-AB",
        status: "OPEN",
        sku_id: "SKU-STD",
        planned_qty: "6",
        issued_qty: "0",
        received_qty: "0",
        source_warehouse_id: "WH-A",
        target_warehouse_id: "WH-B"
      }]
    }), { status: 200 })));
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <TransferPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    await waitFor(() => expect(screen.getByText("SKU-STD")).toBeTruthy());
    expect(screen.getByText("6")).toBeTruthy();
    expect(screen.getByText("WH-B")).toBeTruthy();
  });
});
