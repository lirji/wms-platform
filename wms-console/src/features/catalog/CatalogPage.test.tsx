import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { CatalogPage } from "./CatalogPage";

describe("CatalogPage", () => {
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
