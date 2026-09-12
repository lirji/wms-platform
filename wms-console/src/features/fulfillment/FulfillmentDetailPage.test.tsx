import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { FulfillmentDetailPage } from "./FulfillmentDetailPage";

describe("FulfillmentDetailPage", () => {
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
