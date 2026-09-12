import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { OutboundDetailPage } from "./OutboundDetailPage";

describe("OutboundDetailPage", () => {
  it("exposes pick, pack, ship and cancel commands", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/outbound/OB-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["outbound.pick", "outbound.pack", "outbound.ship"] }}>
            <Routes>
              <Route path="/w/:warehouseId/outbound/:outboundOrderId" element={<OutboundDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "规划任务" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^拣货$/ }));
    expect(screen.getByRole("button", { name: "确认拣货" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^包装$/ }));
    expect(screen.getByRole("button", { name: "确认包装" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^部分发运$/ }));
    expect(screen.getByRole("button", { name: "确认发运" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^取消未拣回库$/ }));
    expect(screen.getByRole("button", { name: "取消剩余" })).toBeTruthy();
  });
});
