import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { ActionEffectDetailPage } from "./ActionEffectDetailPage";

describe("ActionEffectDetailPage", () => {
  it("exposes safe reauthorization without inventing retry", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/effects/EF-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["task.claim"] }}>
            <Routes>
              <Route path="/w/:warehouseId/effects/:effectId" element={<ActionEffectDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "动作效果 EF-1" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "登记下一尝试" })).toBeTruthy();
    expect(screen.getByText(/UNKNOWN 不得直接重做/)).toBeTruthy();
  });
});
