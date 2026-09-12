import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { TaskDetailPage } from "./TaskDetailPage";

describe("TaskDetailPage", () => {
  it("exposes claim when the token has task.claim", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/tasks/T1?taskType=PICK"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["task.claim"] }}>
            <Routes>
              <Route path="/w/:warehouseId/tasks/:taskId" element={<TaskDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "仓任务 T1" })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "提交命令" }));
    expect(screen.getByRole("button", { name: "领取任务" })).toBeTruthy();
  });

  it("hides claim when the token has no task.claim", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/tasks/T1?taskType=PICK"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: [] }}>
            <Routes>
              <Route path="/w/:warehouseId/tasks/:taskId" element={<TaskDetailPage />} />
            </Routes>
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.queryByRole("button", { name: "提交命令" })).toBeNull();
  });
});
