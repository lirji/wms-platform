import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { HomePage } from "./HomePage";

describe("HomePage", () => {
  it("shows workbench modules without inventing warehouses", () => {
    render(
      <MemoryRouter>
        <WorkspaceProvider value={{ token: undefined, warehouseId: "" }}>
          <HomePage />
        </WorkspaceProvider>
      </MemoryRouter>
    );
    expect(screen.getByRole("heading", { name: "仓库工作台" })).toBeTruthy();
    expect(screen.getByText("入库工作台")).toBeTruthy();
    expect(screen.getByText(/还没有可作业的仓库/)).toBeTruthy();
    expect(screen.queryByText("Internal Server Error")).toBeNull();
  });
});
