import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ListPager } from "./ListPager";

describe("ListPager", () => {
  it("uses default then primary and keeps the pager on the right", () => {
    render(<ListPager prevDisabled nextDisabled={false} countLabel="本页 4 条" />);
    const first = screen.getByRole("button", { name: /首页/ });
    const next = screen.getByRole("button", { name: /下一页/ });
    expect(first.className).toMatch(/ant-btn-default/);
    expect(next.className).toMatch(/ant-btn-primary/);
    expect(screen.getByText("本页 4 条")).toBeTruthy();
    expect(document.querySelector(".list-pager")).toBeTruthy();
  });
});
