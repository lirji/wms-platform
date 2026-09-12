import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { JobsPage } from "./JobsPage";

describe("JobsPage", () => {
  it("keeps job runs and warehouse tasks on the same route", () => {
    render(
      <AppProviders>
        <MemoryRouter initialEntries={["/w/WH-A/jobs?taskType=PICK"]}>
          <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["task.read"] }}>
            <JobsPage />
          </WorkspaceProvider>
        </MemoryRouter>
      </AppProviders>
    );
    expect(screen.getByRole("heading", { name: "任务与设备" })).toBeTruthy();
    expect(screen.getByText("仓执行任务")).toBeTruthy();
    expect(screen.getByText("序列号恢复")).toBeTruthy();
    expect(screen.getByText("消息积压")).toBeTruthy();
    expect(screen.getByLabelText("任务类型")).toBeTruthy();
  });
});
