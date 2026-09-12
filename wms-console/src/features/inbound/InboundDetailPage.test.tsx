import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { InboundDetailPage } from "./InboundDetailPage";

describe("InboundDetailPage", () => {
  it("exposes receive, quality and putaway commands", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/inbound/ASN-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A" }}>
            <Routes>
              <Route path="/w/:warehouseId/inbound/:inboundOrderId" element={<InboundDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "入库单 ASN-1" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "提交收货" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "记录质检" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "提交上架" })).toBeTruthy();
  });
});
