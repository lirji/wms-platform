import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { CatalogPage } from "./CatalogPage";

describe("CatalogPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows 未绑定 when a standard lot has no produced or expiry instant", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = String(url);
      if (path.includes("/lots")) {
        return new Response(JSON.stringify({
          items: [{ id: "WH-A-LOT-STD", lot_code: "LOT-STD", sku_id: "SKU-LOT", produced_at: null, expires_at: null }]
        }), { status: 200 });
      }
      return new Response(JSON.stringify({ items: [] }), { status: 200 });
    }));
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <CatalogPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    await waitFor(() => expect(screen.getAllByText("未绑定").length).toBeGreaterThan(0));
    expect(screen.getByText("LOT-STD")).toBeTruthy();
  });

  it("shows create commands when the token can write masterdata", () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["masterdata.write"] }}>
            <CatalogPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "商品 / 库位" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "创建商品" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "创建库位" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "登记批次" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "更多" }));
    expect(screen.getByRole("button", { name: "创建仓库" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "追加单位" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "查看门禁" })).toBeTruthy();
  });

  it("hides create commands when the token has no write scope", () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <CatalogPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.queryByRole("button", { name: "创建商品" })).toBeNull();
    expect(screen.queryByRole("button", { name: "创建仓库" })).toBeNull();
  });
});
