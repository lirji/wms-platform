import { FormEvent, useState } from "react";
import { api } from "../../api/client";
import { pageItems, type ItemRecord } from "../../api/envelope";
import { DataTable } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { useWorkspace } from "../../shell/WorkspaceContext";

export function ReconPage() {
  const { token, warehouseId } = useWorkspace();
  const [cutoffId, setCutoffId] = useState("");
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  async function load(event: FormEvent) {
    event.preventDefault();
    if (!token || !warehouseId || warehouseId === "_") {
      return;
    }
    setBusy(true);
    try {
      const body = await api(
        `/api/wms/v1/warehouses/${warehouseId}/reconciliation-cases?cutoffId=${encodeURIComponent(cutoffId)}`,
        token
      );
      setRows(pageItems(body));
      setError(undefined);
      setLoaded(true);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <PageHead title="对账差异" sub="按仓与 cutoff 查询服务端差异，页面不预置差异列表。" />
      <form className="panel" onSubmit={load}>
        <label>
          cutoffId
          <input value={cutoffId} onChange={(event) => setCutoffId(event.target.value)} required />
        </label>
        <button className="btn btn-primary" type="submit" disabled={busy || !cutoffId || !warehouseId || warehouseId === "_"}>
          {busy ? "查询中" : "加载差异"}
        </button>
      </form>
      {error ? errorBanner(error) : null}
      {loaded && rows.length === 0 ? <StatusBanner kind="empty" title={`当前 cutoff ${cutoffId || "(未填)"} 没有差异`} /> : null}
      <DataTable
        rows={rows}
        columns={[
          { key: "id", label: "差异", keys: ["id", "caseId"] },
          { key: "status", label: "状态", keys: ["status"] },
          { key: "skuId", label: "SKU", keys: ["skuId"] },
          { key: "qty", label: "数量", qty: true, keys: ["qty", "deltaQty"] }
        ]}
      />
    </section>
  );
}
