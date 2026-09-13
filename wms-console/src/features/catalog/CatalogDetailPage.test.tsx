import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { LocationDetailPage, LotDetailPage, SkuDetailPage } from "./CatalogDetailPage";

function renderDetail(path: string, scopes = ["masterdata.write"]) {
  return render(
    <AppProviders>
      <MemoryRouter initialEntries={[path]}>
        <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes }}>
          <Routes>
            <Route path="/w/:warehouseId/catalog/skus/:skuId" element={<SkuDetailPage />} />
            <Route path="/w/:warehouseId/catalog/locations/:locationId" element={<LocationDetailPage />} />
            <Route path="/w/:warehouseId/catalog/lots/:lotId" element={<LotDetailPage />} />
            <Route path="/w/:warehouseId/catalog" element={<div>catalog-list</div>} />
          </Routes>
        </WorkspaceProvider>
      </MemoryRouter>
    </AppProviders>
  );
}

describe("CatalogDetailPage", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads sku policy and units from the published getSku envelope", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      expect(String(url)).toContain("/api/wms/v1/skus/SKU-SN");
      return new Response(JSON.stringify({
        id: "SKU-SN",
        code: "SKU-SN",
        name: "序列号商品",
        base_unit: "EA",
        quantity_scale: 0,
        lot_enabled: 0,
        serial_enabled: 1,
        expiry_enabled: 0,
        policy_version: 1,
        state: "ACTIVE",
        version: 1,
        units: [{ id: "U-EA", unit_code: "EA", numerator: "1", denominator: "1", policy_version: 1 }]
      }), { status: 200 });
    }));
    renderDetail("/w/WH-A/catalog/skus/SKU-SN");
    expect(await screen.findByRole("heading", { name: "商品 SKU-SN" })).toBeTruthy();
    expect(screen.getByText("序列号商品")).toBeTruthy();
    expect(screen.getByText("是")).toBeTruthy();
    expect(screen.getAllByText("EA").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "追加单位" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "返回主数据" }).getAttribute("href")).toBe("/w/WH-A/catalog");
  });

  it("reads location capacity and a present gate", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const href = String(url);
      if (href.includes("/gate")) {
        return new Response(JSON.stringify({
          location_id: "WH-A-RCV",
          state: "OPEN",
          reason_code: "",
          fence_epoch: 0,
          version: 1
        }), { status: 200 });
      }
      return new Response(JSON.stringify({
        id: "WH-A-RCV",
        code: "RCV",
        zone_code: "IN",
        location_type: "RECEIVING",
        capacity_qty: "100",
        capacity_unit: "EA",
        state: "ACTIVE",
        version: 1
      }), { status: 200 });
    }));
    renderDetail("/w/WH-A/catalog/locations/WH-A-RCV");
    expect(await screen.findByRole("heading", { name: "库位 WH-A-RCV" })).toBeTruthy();
    await waitFor(() => expect(screen.getByText("RECEIVING")).toBeTruthy());
    expect(screen.getByText("100")).toBeTruthy();
    expect(screen.getByText("OPEN")).toBeTruthy();
  });

  it("reads lot expiry fields and links the sku", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      id: "WH-A-LOT-EXPIRED",
      owner_id: "OWNER-A",
      sku_id: "SKU-EXPIRED",
      lot_code: "LOT-EXPRED",
      business_lot_key: "LOT-EXPRED",
      produced_at: "2024-01-01T00:00:00Z",
      expires_at: "2024-02-01T00:00:00Z",
      expiry_rule_version: 1,
      version: 1
    }), { status: 200 })));
    renderDetail("/w/WH-A/catalog/lots/WH-A-LOT-EXPIRED");
    expect(await screen.findByRole("heading", { name: "批次 WH-A-LOT-EXPIRED" })).toBeTruthy();
    expect(screen.getByText("OWNER-A")).toBeTruthy();
    expect(screen.getByRole("link", { name: "SKU-EXPIRED" }).getAttribute("href")).toBe(
      "/w/WH-A/catalog/skus/SKU-EXPIRED"
    );
  });
});
