import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { PickPage } from "./PickPage";

describe("PickPage", () => {
  it("requires outbound.pick and does not invent an ownerEpoch", () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["outbound.pick"] }}>
            <PickPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "PDA 拣货" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "回车提交" })).toBeTruthy();
    expect(screen.getByText(/不能默认 1/)).toBeTruthy();
  });

  it("blocks submit without outbound.pick", () => {
    render(
      <AppProviders>
        <MemoryRouter>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <PickPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByText(/没有 outbound.pick/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "回车提交" }).hasAttribute("disabled")).toEqual(true);
  });
});
