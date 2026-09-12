import { FormEvent, useRef, useState } from "react";
import { Button, Card, Form, Input, type InputRef, Space, Typography } from "antd";
import { api, clearKey, rememberKey } from "../../api/client";
import { field, type ItemRecord } from "../../api/envelope";
import { hasScope } from "../../auth/can";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { countText, receiptObservation } from "../../shared/serial/serialIds";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { playScanTone } from "./tone";

export function ReceivePage() {
  const { token, warehouseId, scopes } = useWorkspace();
  const [orderId, setOrderId] = useState("");
  const [lineId, setLineId] = useState("");
  const [qty, setQty] = useState("");
  const [scan, setScan] = useState("");
  const [feedback, setFeedback] = useState("等待扫码");
  const [tone, setTone] = useState<"ok" | "err" | "idle">("idle");
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const [locationId, setLocationId] = useState("");
  const [lotId, setLotId] = useState("");
  const [serialText, setSerialText] = useState("");
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const scanRef = useRef<InputRef>(null);
  const scanSequence = useRef(0);

  async function submit() {
    const nextLine = scan || lineId;
    if (!token || !orderId || !nextLine || !qty || !locationId || !lotId || submitting.current || !warehouseId || warehouseId === "_") {
      return;
    }
    submitting.current = true;
    setBusy(true);
    const operation = `receive:${warehouseId}:${orderId}:${nextLine}`;
    const key = rememberKey(operation);
    setFeedback("提交中");
    try {
      const serialObservation = receiptObservation(serialText);
      const body = await api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${orderId}/receipts`, token, {
        method: "POST",
        idempotencyKey: key,
        body: {
          lineId: nextLine,
          locationId,
          lotId,
          qty: serialObservation ? countText(serialObservation.serialIds) : qty,
          clientOperationId: key,
          receiptPartId: `PART-${key}`,
          scanSequence: scanSequence.current,
          ...(serialObservation ? { serialObservation } : {})
        }
      }) as ItemRecord;
      const applied = field(body, "stockSyncStatus") === "APPLIED";
      if (applied) {
        clearKey(operation);
        scanSequence.current += 1;
      }
      setResult(body);
      setError(undefined);
      setTone("ok");
      setFeedback(applied ? "收货已同步" : "扫码已受理，库存待同步");
      playScanTone(true);
    } catch (caught) {
      setError(caught);
      setTone("err");
      setFeedback("扫码失败");
      playScanTone(false);
    } finally {
      submitting.current = false;
      setBusy(false);
      window.setTimeout(() => scanRef.current?.focus(), 0);
    }
  }

  function onScan(event: FormEvent) {
    event.preventDefault();
    if (scan) {
      setLineId(scan);
    }
    void submit();
    setScan("");
  }

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex", maxWidth: 520, margin: "0 auto" }}>
      <PageHead eyebrow={warehouseId || "未选仓"} title="PDA 收货" sub="扫码枪连续输入，成功失败同时用文字说明，不只靠颜色。" />
      {!hasScope(scopes, "inbound.receive") ? errorBanner({ status: 403, code: "SCOPE_FORBIDDEN", message: "当前令牌没有 inbound.receive" }) : null}
      <Card>
        <Typography.Title level={5} type={tone === "err" ? "danger" : tone === "ok" ? "success" : "secondary"} aria-live="assertive">
          {feedback}
        </Typography.Title>
        {error ? errorBanner(error) : null}
        {result ? (
          <StatusBanner
            kind="accepted"
            title="收货已记录实物，库存同步待查询"
            operationId={field(result, "operationId", "commandId")}
            detail={`physicalStatus=${field(result, "physicalStatus")} stockSyncStatus=${field(result, "stockSyncStatus")}`}
          />
        ) : null}
        <Form layout="vertical" onSubmitCapture={onScan} style={{ marginTop: 16 }}>
          <Form.Item label="入库单" required>
            <Input size="large" value={orderId} onChange={(event) => setOrderId(event.target.value)} />
          </Form.Item>
          <Form.Item label="行/扫码" required>
            <Input ref={scanRef} size="large" autoFocus value={scan || lineId} onChange={(event) => setScan(event.target.value)} />
          </Form.Item>
          <Form.Item label="收货库位" required>
            <Input size="large" value={locationId} onChange={(event) => setLocationId(event.target.value)} />
          </Form.Item>
          <Form.Item label="货品批次" required extra="不按批次管理的货品填写 NO_LOT；其余填写已建档批次。">
            <Input size="large" value={lotId} onChange={(event) => setLotId(event.target.value)} />
          </Form.Item>
          <Form.Item label="数量" required extra="填写序列号时按身份个数提交。">
            <Input size="large" inputMode="decimal" value={qty} onChange={(event) => setQty(event.target.value)} />
          </Form.Item>
          <Form.Item label="序列号观察" extra="序列号 SKU 每行一个。普通货品留空。">
            <Input.TextArea rows={3} value={serialText} onChange={(event) => setSerialText(event.target.value)} placeholder={"SN-001\nSN-002"} />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={busy} disabled={busy || !hasScope(scopes, "inbound.receive")}>回车提交</Button>
        </Form>
      </Card>
    </Space>
  );
}
