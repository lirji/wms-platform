import { FormEvent, useMemo, useState } from "react";
import { api, rememberKey } from "../../api/client";
import { field, type ItemRecord } from "../../api/envelope";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function ReceivePage() {
  const { token, warehouseId } = useWorkspace();
  const [orderId, setOrderId] = useState("");
  const [lineId, setLineId] = useState("");
  const [qty, setQty] = useState("");
  const [scan, setScan] = useState("");
  const [feedback, setFeedback] = useState("等待扫码");
  const [tone, setTone] = useState<"ok" | "err" | "idle">("idle");
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const key = useMemo(
    () => rememberKey(`receive:${warehouseId}:${orderId}:${lineId}`),
    [warehouseId, orderId, lineId]
  );

  async function submit() {
    const nextLine = scan || lineId;
    if (!token || !orderId || !nextLine || !qty || !warehouseId || warehouseId === "_") {
      return;
    }
    setFeedback("提交中");
    try {
      const body = await api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${orderId}/receipts`, token, {
        method: "POST",
        idempotencyKey: key,
        body: { lineId: nextLine, qty, clientOperationId: key, receiptPartId: `PART-${key}` }
      });
      setResult(body as ItemRecord);
      setError(undefined);
      setTone("ok");
      setFeedback("扫码已受理");
    } catch (caught) {
      setError(caught);
      setTone("err");
      setFeedback("扫码失败");
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
    <section className="pda">
      <PageHead eyebrow={warehouseId || "未选仓"} title="PDA 收货" sub="扫码枪连续输入，成功失败同时用文字说明，不只靠颜色。" />
      <div className="pda-card">
        <p aria-live="assertive" className={`tone tone-${tone}`}>{feedback}</p>
        {error ? errorBanner(error) : null}
        {result ? (
          <StatusBanner
            kind="accepted"
            title="收货已记录实物，库存同步待查询"
            operationId={field(result, "operationId", "commandId")}
            detail={`physicalStatus=${field(result, "physicalStatus")} stockSyncStatus=${field(result, "stockSyncStatus")}`}
          />
        ) : null}
        <form onSubmit={onScan}>
          <label>
            入库单
            <input value={orderId} onChange={(event) => setOrderId(event.target.value)} required />
          </label>
          <label>
            行/扫码
            <input value={scan || lineId} onChange={(event) => setScan(event.target.value)} autoFocus required />
          </label>
          <label>
            数量（字符串）
            <input value={qty} onChange={(event) => setQty(event.target.value)} inputMode="decimal" required />
          </label>
          <button className="btn btn-primary" type="submit">回车提交</button>
        </form>
      </div>
    </section>
  );
}
