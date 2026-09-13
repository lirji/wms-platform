import { FormEvent, useState } from "react";
import { Button, Card, Form, Input } from "antd";
import { useParams } from "react-router-dom";
import { api } from "../../api/client";
import { asRecord, field } from "../../api/envelope";
import { CommandCard } from "../../shared/command/CommandCard";
import { CommandCol, DocumentWorkbench } from "../../shared/document/DocumentWorkbench";
import { SerialChoiceQuery } from "../../shared/serial/SerialChoiceQuery";
import { SerialExecutionField } from "../../shared/serial/SerialExecutionField";
import { serialExecution } from "../../shared/serial/serialIds";
import { errorBanner } from "../../shared/ui/errorBanner";
import { useDocument } from "../../shared/useDocument";
import { useWorkspace } from "../../shell/WorkspaceContext";

function postingContext(transferId: string, values: Record<string, string>) {
  return {
    documentId: transferId,
    ownerId: values.ownerId,
    skuId: values.skuId,
    baseUnit: values.baseUnit || "EA",
    sourceLocationId: values.sourceLocationId,
    lotId: values.lotId || "NO_LOT",
    qualityCode: values.qualityCode
  };
}

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
  const [commandId, setCommandId] = useState("");
  const [commandTick, setCommandTick] = useState(0);
  const commandPath = transferId && commandId.trim()
    ? `/api/wms/v1/transfers/${transferId}/serial-commands/${encodeURIComponent(commandId.trim())}`
    : undefined;
  const command = useDocument(token, commandTick > 0 ? commandPath : undefined, commandTick);

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
      extra={(
        <>
          {source === warehouseId || !source ? <SerialChoiceQuery kind="selectable" warehouseId={warehouseId} /> : null}
          <Card size="small" title="序列调拨命令" extra="Outbox 已发送不等于 COMPLETE。">
            <Form layout="inline" className="list-toolbar" onSubmitCapture={(event: FormEvent) => {
              event.preventDefault();
              setCommandTick((current) => current + 1);
            }}>
              <Form.Item label="commandId" required>
                <Input value={commandId} onChange={(event) => setCommandId(event.target.value)} />
              </Form.Item>
              <Button type="primary" htmlType="submit" disabled={!token || !commandId.trim()} loading={command.loading}>查询</Button>
            </Form>
            {command.error ? errorBanner(command.error) : null}
            {commandTick > 0 && !command.loading && !command.error ? (
              <p style={{ marginTop: 12 }}>
                状态 {field(asRecord(command.record), "state") || "—"} · 仓 {field(command.record, "warehouseId") || "—"} · 数量 {field(command.record, "quantity") || "—"}
              </p>
            ) : null}
          </Card>
        </>
      )}
      commands={(
        <>
          <CommandCol title="源仓发出" requireScope="transfer.create">
            <CommandCard
              embedded
              requireScope="transfer.create"
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
          <CommandCol title="序列号发出" requireScope="transfer.create">
            <CommandCard
              embedded
              requireScope="transfer.create"
              title="序列号发出"
              hint="处理中不累计已发出数量。数量必须等于身份集合大小。epoch 必须来自源仓查询。"
              operation={`transfer-serial-issue:${transferId}`}
              submitLabel="发出身份"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => {
                const selection = serialExecution(values.serialExecution || "");
                if (!selection) {
                  throw new Error("序列号发出必须提交当前身份与 ownerEpoch");
                }
                return api(`/api/wms/v1/transfers/${transferId}/serial-issues`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    lineId: values.lineId,
                    qty: selection.identities.length,
                    postingContext: postingContext(transferId, values),
                    selection
                  }
                });
              }}
            >
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="货主" name="ownerId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="单位" name="baseUnit" initialValue="EA"><Input /></Form.Item>
              <Form.Item label="源库位" name="sourceLocationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="批次" name="lotId" initialValue="NO_LOT" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="质量" name="qualityCode" initialValue="GOOD" rules={[{ required: true }]} extra="源发出填 GOOD。"><Input /></Form.Item>
              <SerialExecutionField required extra="先查本页可选序列号。不要手写旧代际。" />
            </CommandCard>
          </CommandCol>
          <CommandCol title="目的接收授权" requireScope="transfer.authorizeReceipt">
            <CommandCard
              embedded
              requireScope="transfer.authorizeReceipt"
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
          <CommandCol title="目的仓接收" requireScope="transfer.receive">
            <CommandCard
              embedded
              requireScope="transfer.receive"
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
          <CommandCol title="序列号接收" requireScope="transfer.receive">
            <CommandCard
              embedded
              requireScope="transfer.receive"
              title="序列号接收"
              hint="额度与 SN 只能绑定一个原命令。目的接收 qualityCode 必须是 HOLD。"
              operation={`transfer-serial-receive:${transferId}`}
              submitLabel="接收身份"
              disabled={!token}
              onDone={reload}
              onRun={(key, values) => {
                const selection = serialExecution(values.serialExecution || "");
                if (!selection) {
                  throw new Error("序列号接收必须提交原发出身份与 ownerEpoch");
                }
                return api(`/api/wms/v1/warehouses/${values.warehouseId || target || warehouseId}/serial-transfer-receipts`, token, {
                  method: "POST",
                  idempotencyKey: key,
                  body: {
                    transferId,
                    lineId: values.lineId,
                    authorizationId: values.authorizationId,
                    tokenVersion: Number(values.tokenVersion || "0"),
                    qty: selection.identities.length,
                    postingContext: postingContext(transferId, values),
                    selection
                  }
                });
              }}
            >
              <Form.Item label="目的仓" name="warehouseId" initialValue={target || warehouseId}><Input /></Form.Item>
              <Form.Item label="行" name="lineId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="authorizationId" name="authorizationId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="tokenVersion" name="tokenVersion" initialValue="0"><Input /></Form.Item>
              <Form.Item label="货主" name="ownerId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="SKU" name="skuId" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="单位" name="baseUnit" initialValue="EA"><Input /></Form.Item>
              <Form.Item label="目的库位" name="sourceLocationId" extra="契约字段仍是 sourceLocationId，填本次接收库位。" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="批次" name="lotId" initialValue="NO_LOT" rules={[{ required: true }]}><Input /></Form.Item>
              <Form.Item label="质量" name="qualityCode" initialValue="HOLD" rules={[{ required: true }]} extra="目的接收必须 HOLD。"><Input /></Form.Item>
              <SerialExecutionField required extra="必须是已发出的原身份。" />
            </CommandCard>
          </CommandCol>
          <CommandCol title="确认在途损耗" requireScope="stock.move">
            <CommandCard
              embedded
              danger
              requireScope="stock.move"
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
