import { useState } from "react";
import { Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { field } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function TransferDetailPage() {
  const { warehouseId = "", transferId = "" } = useParams();
  const { token } = useWorkspace();
  const [tick, setTick] = useState(0);
  const { record, error, loading } = useDocument(
    token,
    transferId ? `/api/wms/v1/transfers/${transferId}?warehouseId=${encodeURIComponent(warehouseId)}` : undefined,
    tick
  );
  const reload = () => setTick((current) => current + 1);
  const source = field(record, "sourceWarehouseId");
  const target = field(record, "targetWarehouseId");

  return (
    <DocumentWorkbench
      backTo={`/w/${warehouseId}/transfers`}
      backLabel="返回调拨列表"
      title={`调拨单 ${transferId}`}
      sub={`源仓 ${source || "—"} → 目的仓 ${target || "—"}。接收必须先拿额度 token。`}
      loading={loading}
      error={error}
      record={record}
      extraColumns={[
        { key: "planned", label: "计划", qty: true, keys: ["planned_qty"] },
        { key: "issued", label: "已发", qty: true, keys: ["issued_qty"] },
        { key: "received", label: "已收", qty: true, keys: ["received_qty"] },
        { key: "loss", label: "损耗", qty: true, keys: ["loss_confirmed_qty"] }
      ]}
      commands={(
        <>
          <CommandCol>
            <CommandCard
              title="源仓发出"
              hint="不能超过计划数量。当前令牌必须能访问源仓。"
              operation={`transfer-issue:${transferId}`}
              submitLabel="确认发出"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/transfers/${transferId}/issues`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { lineId: values.lineId, qty: values.qty, clientOperationId: key }
              })}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="目的接收授权"
              hint="占用在途可收额度，返回 authorizationId 与 tokenVersion。"
              operation={`transfer-auth:${transferId}`}
              submitLabel="申请额度"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/transfers/${transferId}/receipt-authorizations`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  lineId: values.lineId,
                  quantity: values.qty,
                  targetClientOperationId: key,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="目的仓接收"
              hint="必须带授权与版本。仓库必须是目的仓。"
              operation={`transfer-receive:${transferId}`}
              submitLabel="确认接收"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/warehouses/${values.warehouseId || target || warehouseId}/transfer-receipts`, token, {
                method: "POST",
                idempotencyKey: key,
                body: {
                  transferId,
                  lineId: values.lineId,
                  authorizationId: values.authorizationId,
                  tokenVersion: Number(values.tokenVersion || "0"),
                  qty: values.qty,
                  clientOperationId: key
                }
              })}
            >
              <Form.Item label="目的仓" name="warehouseId" initialValue={target || warehouseId}><Input /></Form.Item>
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="authorizationId" name="authorizationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="tokenVersion" name="tokenVersion" initialValue="0"><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
          <CommandCol>
            <CommandCard
              title="确认在途损耗"
              hint="与接收额度竞争同一行。超过在途可定量拒绝。"
              operation={`transfer-loss:${transferId}`}
              submitLabel="确认损耗"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => api(`/api/wms/v1/transfers/${transferId}/losses`, token, {
                method: "POST",
                idempotencyKey: key,
                body: { lineId: values.lineId, qty: values.qty, clientOperationId: key }
              })}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="数量" name="qty" rules={[{ required: true }]}><Input inputMode="decimal" /></Form.Item>
            </CommandCard>
          </CommandCol>
        </>
      )}
    />
  );
}
