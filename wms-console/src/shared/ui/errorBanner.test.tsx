import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { errorBanner } from "./errorBanner";

describe("errorBanner", () => {
  it("does not call a 401 session error 权限不足", () => {
    render(errorBanner({ status: 401, message: "UNAUTHENTICATED" }));
    expect(screen.getByText("登录已过期或尚未登录")).toBeTruthy();
    expect(screen.queryByText("权限不足")).toBeNull();
  });

  it("shows token warehouses and scopes on 403", () => {
    render(
      <WorkspaceProvider value={{ warehouseId: "WH-A", enterpriseId: "ENT-DEMO", warehouses: ["WH-A"], scopes: ["inbound.read"] }}>
        {errorBanner({ status: 403, message: "WAREHOUSE_FORBIDDEN", code: "WAREHOUSE_FORBIDDEN" })}
      </WorkspaceProvider>
    );
    expect(screen.getByText("WAREHOUSE_FORBIDDEN")).toBeTruthy();
    expect(screen.getByText(/warehouses=WH-A/)).toBeTruthy();
    expect(screen.getByText(/scope=inbound.read/)).toBeTruthy();
  });
});
