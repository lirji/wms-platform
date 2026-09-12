import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { StockPage } from "./StockPage";

describe("StockPage", () => {
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
