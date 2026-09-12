import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { LoginPage } from "./LoginPage";

describe("LoginPage", () => {
  it("renders the warehouse login desk", () => {
    render(
      <MemoryRouter initialEntries={["/login?returnTo=%2F"]}>
        <Routes>
          <Route path="/login" element={<LoginPage user={null} />} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByRole("heading", { name: "登录后进入作业台" })).toBeTruthy();
    expect(screen.queryByText("统一登录")).toBeNull();
    expect(screen.getByRole("button", { name: "使用统一身份登录" })).toBeTruthy();
    expect(screen.getByText(/wms-platform/)).toBeTruthy();
  });
});
