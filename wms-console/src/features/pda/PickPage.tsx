import { FormEvent, useRef, useState } from "react";
import { Button, Card, Form, Input, type InputRef, Space, Typography } from "antd";
import { api, clearKey, rememberKey } from "../../api/client";
import { field, type ItemRecord } from "../../api/envelope";
import { hasScope } from "../../auth/can";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { countText, serialExecution } from "../../shared/serial/serialIds";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { appendIdentityLine } from "./scanIdentity";
import { playScanTone } from "./tone";

export function PickPage() {
  const { token, warehouseId, scopes } = useWorkspace();
  const [taskId, setTaskId] = useState("");
  const [lotId, setLotId] = useState("");
  const [qty, setQty] = useState("");
  const [epoch, setEpoch] = useState("");
  const [serialText, setSerialText] = useState("");
  const [scan, setScan] = useState("");
  const [feedback, setFeedback] = useState("等待扫码");
  const [tone, setTone] = useState<"ok" | "err" | "idle">("idle");
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const scanRef = useRef<InputRef>(null);

  async function submit(nextSerialText = serialText) {
    if (!token || !taskId || !lotId || submitting.current || !warehouseId || warehouseId === "_") {
      return;
    }
    submitting.current = true;
    setBusy(true);
    const operation = `pda-pick:${warehouseId}:${taskId}`;
    const key = rememberKey(operation);
    setFeedback("提交中");
    try {
      const selection = serialExecution(nextSerialText);
      if (!selection && !qty) {
        throw new Error("数量或身份清单至少填一项");
      }
      const body = await api(`/api/wms/v1/warehouses/${warehouseId}/tasks/${taskId}/picks`, token, {
        method: "POST",
        idempotencyKey: key,
        body: {
          lotId,
          qty: selection ? countText(selection.identities.map((item) => item.serialId)) : qty,
          clientOperationId: key,
          ...(selection ? { serialExecution: selection } : {})
        }
      }) as ItemRecord;
      const applied = field(body, "stockSyncStatus") === "APPLIED";
      if (applied) {
        clearKey(operation);
      }
      setResult(body);
      setError(undefined);
      setTone("ok");
      setFeedback(applied ? "拣货已同步" : "拣货已受理，库存待同步");
      playScanTone(true);
    } catch (caught) {
      setError(caught);
      setTone("err");
      setFeedback("拣货失败");
      playScanTone(false);
    } finally {
      submitting.current = false;
      setBusy(false);
      window.setTimeout(() => scanRef.current?.focus(), 0);
    }
  }

  function onScan(event: FormEvent) {
    event.preventDefault();
    let nextText = serialText;
    if (scan.trim()) {
      try {
        nextText = appendIdentityLine(serialText, scan, epoch);
        setSerialText(nextText);
        setScan("");
        setError(undefined);
      } catch (caught) {
        setError(caught);
        setTone("err");
        setFeedback("扫码未入清单");
        playScanTone(false);
        setScan("");
        return;
      }
    }
    void submit(nextText);
  }

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex", maxWidth: 520, margin: "0 auto" }}>
      <PageHead eyebrow={warehouseId || "未选仓"} title="PDA 拣货" sub="扫码只带 SN。ownerEpoch 必须来自仓内查询，不能默认 1。成功失败同时用文字说明。" />
      {!hasScope(scopes, "outbound.pick") ? errorBanner({ status: 403, code: "SCOPE_FORBIDDEN", message: "当前令牌没有 outbound.pick" }) : null}
      <Card>
        <Typography.Title level={5} type={tone === "err" ? "danger" : tone === "ok" ? "success" : "secondary"} aria-live="assertive">
          {feedback}
        </Typography.Title>
        {error ? errorBanner(error) : null}
        {result ? (
          <StatusBanner
            kind="accepted"
            title="拣货已记录实物，库存同步待查询"
            operationId={field(result, "operationId", "commandId")}
            detail={`physicalStatus=${field(result, "physicalStatus")} stockSyncStatus=${field(result, "stockSyncStatus")}`}
          />
        ) : null}
        <Form layout="vertical" onSubmitCapture={onScan} style={{ marginTop: 16 }}>
          <Form.Item label="拣货任务" required>
            <Input size="large" value={taskId} onChange={(event) => setTaskId(event.target.value)} />
          </Form.Item>
          <Form.Item label="批次" required extra="不管理批次的商品填写 NO_LOT。">
            <Input size="large" value={lotId} onChange={(event) => setLotId(event.target.value)} />
          </Form.Item>
          <Form.Item label="当前 ownerEpoch" extra="先查桌面可选序列号，再扫 SN。">
            <Input size="large" inputMode="numeric" value={epoch} onChange={(event) => setEpoch(event.target.value)} />
          </Form.Item>
          <Form.Item label="扫码序列号">
            <Input ref={scanRef} size="large" autoFocus value={scan} onChange={(event) => setScan(event.target.value)} />
          </Form.Item>
          <Form.Item label="数量" extra="填写身份时按身份个数提交。">
            <Input size="large" inputMode="decimal" value={qty} onChange={(event) => setQty(event.target.value)} />
          </Form.Item>
          <Form.Item label="出库身份" extra="每行「序列号 当前ownerEpoch」。普通 SKU 留空。">
            <Input.TextArea rows={3} value={serialText} onChange={(event) => setSerialText(event.target.value)} placeholder={"SN-001 1\nSN-002 1"} />
          </Form.Item>
          <Button type="primary" htmlType="submit" size="large" block loading={busy} disabled={busy || !hasScope(scopes, "outbound.pick")}>回车提交</Button>
        </Form>
      </Card>
    </Space>
  );
}
