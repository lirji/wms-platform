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

  it("does not poll inventory operations for other-domain 202s", async () => {
    const fetchMock = vi.fn(async () => new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    render(
      <AppProviders>
        <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["outbound.pick"] }}>
          <CommandCard
            title="拣货"
            hint="202 不是成功"
            operation="pick:WH-A:OB-1"
            submitLabel="确认拣货"
            requireScope="outbound.pick"
            onRun={async () => ({
              __httpStatus: 202,
              operationId: "OP-OUT",
              physicalStatus: "PICKED",
              stockSyncStatus: "PENDING"
            })}
          >
            <Form.Item label="数量" name="qty" initialValue="1"><Input /></Form.Item>
          </CommandCard>
        </WorkspaceProvider>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "确认拣货" }));
    await waitFor(() => expect(screen.getByText("货已执行，库存待同步")).toBeTruthy());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("stops inventory operation poll when the query is missing", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (String(url).includes("/operations/")) {
        return new Response(JSON.stringify({ code: "NOT_FOUND", message: "没有该作业" }), { status: 404 });
      }
      return new Response("{}", { status: 200 });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(
      <AppProviders>
        <WorkspaceProvider value={{ token: "t", warehouseId: "WH-A", scopes: ["stock.move"] }}>
          <CommandCard
            title="移库"
            hint="202 不是成功"
            operation="move:WH-A"
            submitLabel="提交移库"
            requireScope="stock.move"
            pollOperation
            onRun={async () => ({
              __httpStatus: 202,
              operationId: "OP-1",
              physicalStatus: "MOVED",
              stockSyncStatus: "PENDING"
            })}
          >
            <Form.Item label="数量" name="qty" initialValue="1"><Input /></Form.Item>
          </CommandCard>
        </WorkspaceProvider>
      </AppProviders>
    );
    fireEvent.click(screen.getByRole("button", { name: "提交移库" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const afterFirst = fetchMock.mock.calls.length;
    await new Promise((resolve) => window.setTimeout(resolve, 1200));
    expect(fetchMock.mock.calls.length).toBe(afterFirst);
  });

  it.each(["success", "failure"])("isolates old %s and busy cleanup after changing operation", async (outcome) => {
    let oldResolve: (value: unknown) => void = () => undefined;
    let oldReject: (error: unknown) => void = () => undefined;
    let newResolve: (value: unknown) => void = () => undefined;
    const onDone = vi.fn();
    const oldRun = vi.fn(() => new Promise((resolve, reject) => { oldResolve = resolve; oldReject = reject; }));
    const newRun = vi.fn(() => new Promise((resolve) => { newResolve = resolve; }));
    const view = (operation: string, onRun: typeof oldRun | typeof newRun) => <AppProviders>
      <WorkspaceProvider value={{ token: "fixture", warehouseId: "WH-A", scopes: [] }}>
        <CommandCard title="创建" hint="fixture" operation={operation} submitLabel="提交" onRun={onRun} onDone={onDone}>
          <Form.Item label="单号" name="externalNo" initialValue="initial"><Input /></Form.Item>
        </CommandCard>
      </WorkspaceProvider>
    </AppProviders>;
    const rendered = render(view("create:WH-A", oldRun));
    fireEvent.change(screen.getByLabelText(/单号/), { target: { value: "old-input" } });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() => expect(oldRun).toHaveBeenCalledTimes(1));
    rendered.rerender(view("create:WH-B", newRun));
    expect((screen.getByLabelText(/单号/) as HTMLInputElement).value).toBe("initial");
    expect((screen.getByRole("button", { name: "提交" }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() => expect(newRun).toHaveBeenCalledTimes(1));
    if (outcome === "success") oldResolve({ status: "OLD", __httpStatus: 201 });
    else oldReject({ status: 409, message: "old-conflict" });
    await Promise.resolve();
    await waitFor(() => expect((screen.getByRole("button", { name: /提交/ }) as HTMLButtonElement).disabled).toBe(true));
    expect(screen.queryByText("OLD")).toBeNull();
    expect(screen.queryByText(/old-conflict/)).toBeNull();
    expect(onDone).not.toHaveBeenCalled();
    newResolve({ status: "NEW", __httpStatus: 201 });
    await waitFor(() => expect(onDone).toHaveBeenCalledWith({ status: "NEW", __httpStatus: 201 }));
  });
});
