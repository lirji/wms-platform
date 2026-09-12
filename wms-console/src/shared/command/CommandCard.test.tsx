import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Form, Input } from "antd";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AppProviders } from "../../app/AppProviders";
import { WorkspaceProvider } from "../../shell/WorkspaceContext";
import { CommandCard } from "./CommandCard";

describe("CommandCard", () => {
  afterEach(() => {
    sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it("reuses the same idempotency key after 202 pending", async () => {
    const keys: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url).includes("/operations/")) {
        return new Response(JSON.stringify({ stockSyncStatus: "PENDING", operationId: "OP-1" }), { status: 200 });
      }
      keys.push(String(init?.headers && (init.headers as Record<string, string>)["Idempotency-Key"] || ""));
      return new Response(JSON.stringify({
        operationId: "OP-1",
        physicalStatus: "RECEIVED",
        stockSyncStatus: "PENDING",
        __httpStatus: 202
      }), { status: 202 });
    }));
    render(
      <AppProviders>
        <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["inbound.receive"] }}>
          <CommandCard
            title="收货"
            hint="202 不是成功"
            operation="receive:WH-A:ASN-1"
            submitLabel="提交收货"
            requireScope="inbound.receive"
            onRun={async (key) => {
              keys.push(key);
              return {
                __httpStatus: 202,
                operationId: "OP-1",
                physicalStatus: "RECEIVED",
                stockSyncStatus: "PENDING"
              };
            }}
          >
            <Form.Item label="行" name="lineId" initialValue="L1"><Input /></Form.Item>
          </CommandCard>
        </WorkspaceProvider>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交收货" }));
    await waitFor(() => expect(screen.getByText("货已执行，库存待同步")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "提交收货" }));
    await waitFor(() => expect(keys.filter(Boolean).length).toBeGreaterThanOrEqual(2));
    expect(new Set(keys.filter(Boolean)).size).toBe(1);
  });
});
