import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { ShipPage } from "./ShipPage";

describe("ShipPage", () => {
  it("requires outbound.ship and keeps the posted-not-registered warning", () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["outbound.ship"] }}>
            <ShipPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "PDA 发运" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "回车提交" })).toBeTruthy();
    expect(screen.getByText(/不等于全球登记完成/)).toBeTruthy();
  });
});
