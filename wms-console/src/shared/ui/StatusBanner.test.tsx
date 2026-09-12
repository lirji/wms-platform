import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBanner } from "./StatusBanner";

describe("StatusBanner", () => {
  it("does not treat 202 as business success", () => {
    render(<StatusBanner kind="accepted" title="收货已受理" operationId="CMD-1" />);
    expect(screen.getByText("已受理，库存待同步")).toBeTruthy();
    expect(screen.getByText(/禁止当作业务已成功/)).toBeTruthy();
    expect(screen.queryByText("成功")).toBeNull();
  });

  it("keeps idempotency key on conflict", () => {
    render(<StatusBanner kind="conflict" title="最新版本 4" />);
    expect(screen.getByText(/不会自动更换幂等键/)).toBeTruthy();
  });

  it("explains TCC waiting without a release action", () => {
    render(<StatusBanner kind="tcc" title="TCC_TRYING" />);
    expect(screen.getByText(/没有强制释放按钮/)).toBeTruthy();
  });
});
