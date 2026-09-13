import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { TransferDetailPage } from "./TransferDetailPage";

describe("TransferDetailPage", () => {
  it("exposes quantity and serial transfer commands", { timeout: 30_000 }, () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/transfers/TR-1"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["transfer.create", "transfer.receive", "transfer.authorizeReceipt"] }}>
            <Routes>
              <Route path="/w/:warehouseId/transfers/:transferId" element={<TransferDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "确认发出" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^序列号发出$/ }));
    expect(screen.getByRole("button", { name: "发出身份" })).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: /^序列号接收$/ }));
    expect(screen.getByRole("button", { name: "接收身份" })).toBeTruthy();
    expect(screen.getByText("序列调拨命令")).toBeTruthy();
  });
});
