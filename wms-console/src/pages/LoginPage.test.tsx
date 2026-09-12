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
    expect(screen.getByRole("heading", { name: /先确认身份/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: "使用统一身份登录" })).toBeTruthy();
    expect(screen.getByText(/wms-platform/)).toBeTruthy();
  });
});
