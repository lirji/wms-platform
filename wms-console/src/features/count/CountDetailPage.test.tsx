import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { CountDetailPage } from "./CountDetailPage";

describe("CountDetailPage", () => {
  it("exposes identity observation for serial count lines", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/counts/CP-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["count.record"] }}>
            <Routes>
              <Route path="/w/:warehouseId/counts/:countPlanId" element={<CountDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    fireEvent.click(screen.getByRole("tab", { name: "点数" }));
    expect(screen.getByRole("checkbox", { name: /全部未见/ })).toBeTruthy();
    expect(screen.getByText("实见身份")).toBeTruthy();
  });
});
