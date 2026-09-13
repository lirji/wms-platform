import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { CommandDrawer } from "./CommandDrawer";

describe("CommandDrawer", () => {
  it("opens a centered dialog instead of a side drawer", () => {
    render(
      <AppProviders>
        <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["inbound.receive"] }}>
          <CommandDrawer triggerLabel="提交收货" title="收货" hint="202 不是成功。" requireScope="inbound.receive">
            <button type="button">表单内容</button>
          </CommandDrawer>
        </WorkspaceProvider>
      </AppProviders>
    );
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "提交收货" }));
    expect(screen.getByRole("dialog", { name: "收货" })).toBeTruthy();
    expect(screen.getByText("202 不是成功。")).toBeTruthy();
    expect(document.querySelector(".ant-drawer")).toBeNull();
    expect(document.querySelector(".ant-modal-centered")).toBeTruthy();
  });
});
