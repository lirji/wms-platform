import { FormEvent, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, ApiError, rememberKey } from "../api/client";
import { StatusBanner } from "../components/StatusBanner";

type PageProps = {
  token?: string;
  warehouseId: string;
  setWarehouseId: (id: string) => void;
};

type ItemRecord = Record<string, unknown>;

function asItems(payload: unknown): ItemRecord[] {
  if (payload && typeof payload === "object" && "items" in payload && Array.isArray((payload as { items: unknown }).items)) {
    return (payload as { items: ItemRecord[] }).items;
  }
  return [];
}

function qtyString(value: unknown): string {
  return value == null ? "" : String(value);
}

function errorBanner(error: unknown) {
  const apiError = error as ApiError;
  if (apiError?.status === 403) {
    return <StatusBanner kind="forbidden" title={apiError.message} detail={apiError.code} />;
  }
  if (apiError?.status === 409) {
    return <StatusBanner kind="conflict" title={apiError.message} detail={JSON.stringify(apiError.body)} />;
  }
  if (apiError?.status === 202) {
    return <StatusBanner kind="accepted" title={apiError.message} />;
  }
  return <StatusBanner kind="error" title={apiError?.message ?? "请求失败"} />;
}

export function HomePage({ token, warehouseId, setWarehouseId }: PageProps) {
  const [warehouses, setWarehouses] = useState<ItemRecord[]>([]);
  const [inventory, setInventory] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    if (!token) {
      return;
    }
    setLoading(true);
    api("/api/wms/v1/warehouses", token)
      .then((body) => {
        const items = asItems(body);
        setWarehouses(items);
        if (!warehouseId && items[0]?.id) {
          setWarehouseId(String(items[0].id));
        }
      })
      .catch(setError)
      .finally(() => setLoading(false));
  }, [token, setWarehouseId, warehouseId]);
  useEffect(() => {
    if (!token || !warehouseId) {
      return;
    }
    api(`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`, token)
      .then((body) => setInventory(body as ItemRecord))
      .catch(setError);
  }, [token, warehouseId]);
  const lag = inventory && typeof inventory.lagSeconds === "number" ? inventory.lagSeconds : null;
  return (
    <section>
      <h1>仓库工作台</h1>
      {loading ? <StatusBanner kind="loading" title="正在读取可访问仓库" /> : null}
      {error ? errorBanner(error) : null}
      {warehouses.length === 0 && !loading ? (
        <StatusBanner kind="empty" title="当前身份没有可访问仓库" detail="服务端权限为最终权威" />
      ) : null}
      <label>
        当前仓
        <select value={warehouseId} onChange={(event) => setWarehouseId(event.target.value)}>
          {warehouses.map((row) => (
            <option key={String(row.id)} value={String(row.id)}>
              {String(row.name ?? row.id)}
            </option>
          ))}
        </select>
      </label>
      {inventory ? (
        <StatusBanner
          kind={lag !== null && lag > 30 ? "stale" : "success"}
          title={`asOf ${String(inventory.asOf ?? "未知")}`}
          detail={lag === null ? "延迟未知，写入仍由服务端重校验" : `lagSeconds=${lag}，陈旧时请刷新后等待`}
        />
      ) : null}
      <nav className="cards">
        <Link to="/masterdata">商品/库位</Link>
        <Link to="/inbound">入库</Link>
        <Link to="/inventory">库存台账</Link>
        <Link to="/outbound">出库</Link>
        <Link to="/transfers">调拨</Link>
        <Link to="/counts">盘点</Link>
        <Link to="/jobs">任务/设备</Link>
        <Link to="/recon">对账差异</Link>
        <Link to="/pda">PDA 收货</Link>
      </nav>
    </section>
  );
}

export function MasterdataPage({ token, warehouseId }: PageProps) {
  return (
    <section>
      <h1>商品 / 库位</h1>
      <ResourceList token={token} empty="当前过滤条件下没有主数据" paths={[
        "/api/wms/v1/skus",
        `/api/wms/v1/warehouses/${warehouseId}/locations`
      ]} />
    </section>
  );
}

export function InventoryPage({ token, warehouseId }: PageProps) {
  const [payload, setPayload] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  useEffect(() => {
    if (!token || !warehouseId) {
      return;
    }
    api(`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`, token)
      .then((body) => setPayload(body as ItemRecord))
      .catch(setError);
  }, [token, warehouseId]);
  return (
    <section>
      <h1>库存台账</h1>
      {error ? errorBanner(error) : null}
      {payload ? (
        <StatusBanner
          kind={Number(payload.lagSeconds ?? 0) > 30 ? "stale" : "success"}
          title={`asOf ${String(payload.asOf ?? "")}`}
          detail={`数量均为字符串。lagSeconds=${String(payload.lagSeconds ?? "")}`}
        />
      ) : null}
      <ItemTable rows={payload ? asItems(payload) : []} />
    </section>
  );
}

export function DocumentPage({ token, warehouseId, title, path, extra }: PageProps & { title: string; path: string; extra?: string }) {
  return (
    <section>
      <h1>{title}</h1>
      {extra ? <StatusBanner kind="tcc" title={extra} /> : null}
      <ResourceList token={token} empty={`当前仓 ${warehouseId} 没有单据`} paths={[path]} />
    </section>
  );
}

export function ReconPage({ token, warehouseId }: PageProps) {
  const [cutoffId, setCutoffId] = useState("");
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  async function load(event: FormEvent) {
    event.preventDefault();
    if (!token) {
      return;
    }
    setBusy(true);
    try {
      const body = await api(
        `/api/wms/v1/reconciliation-cases?warehouseIds=${encodeURIComponent(warehouseId)}&cutoffId=${encodeURIComponent(cutoffId)}`,
        token
      );
      setRows(asItems(body));
      setError(undefined);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section>
      <h1>对账差异</h1>
      <form onSubmit={load}>
        <label>
          cutoffId
          <input value={cutoffId} onChange={(event) => setCutoffId(event.target.value)} required />
        </label>
        <button type="submit" disabled={busy || !cutoffId}>{busy ? "查询中" : "加载差异"}</button>
      </form>
      {error ? errorBanner(error) : null}
      {rows.length === 0 ? <StatusBanner kind="empty" title={`当前 cutoff ${cutoffId || "(未填)"} 没有差异`} /> : null}
      <ItemTable rows={rows} />
    </section>
  );
}

export function PdaPage({ token, warehouseId }: PageProps) {
  const { inboundOrderId } = useParams();
  const [orderId, setOrderId] = useState(inboundOrderId ?? "");
  const [lineId, setLineId] = useState("");
  const [qty, setQty] = useState("");
  const [scan, setScan] = useState("");
  const [feedback, setFeedback] = useState<string>("等待扫码");
  const [tone, setTone] = useState<"ok" | "err" | "idle">("idle");
  const [result, setResult] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  const key = useMemo(() => rememberKey(`receive:${warehouseId}:${orderId}:${lineId}`), [warehouseId, orderId, lineId]);
  async function submit() {
    if (!token || !orderId || !lineId || !qty) {
      return;
    }
    setFeedback("提交中");
    try {
      const body = await api(`/api/wms/v1/warehouses/${warehouseId}/inbound-orders/${orderId}/receipts`, token, {
        method: "POST",
        idempotencyKey: key,
        body: { lineId, qty, clientOperationId: key, receiptPartId: `PART-${key}` }
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
      <h1>PDA 收货</h1>
      <p aria-live="assertive" className={`tone tone-${tone}`}>{feedback}</p>
      {error ? errorBanner(error) : null}
      {result ? (
        <StatusBanner
          kind="accepted"
          title="收货已记录实物，库存同步待查询"
          operationId={String(result.operationId ?? result.commandId ?? "")}
          detail={`physicalStatus=${String(result.physicalStatus ?? "")} stockSyncStatus=${String(result.stockSyncStatus ?? "")}`}
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
        <button type="submit">回车提交</button>
      </form>
    </section>
  );
}

function ResourceList({ token, paths, empty }: { token?: string; paths: string[]; empty: string }) {
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const joined = paths.join("|");
  useEffect(() => {
    if (!token) {
      return;
    }
    setLoading(true);
    Promise.all(paths.map((path) => api(path, token)))
      .then((bodies) => setRows(bodies.flatMap((body) => asItems(body))))
      .catch(setError)
      .finally(() => setLoading(false));
  }, [token, joined]);
  return (
    <>
      {loading ? <StatusBanner kind="loading" title="加载中，请勿重复提交" /> : null}
      {error ? errorBanner(error) : null}
      {!loading && rows.length === 0 ? <StatusBanner kind="empty" title={empty} /> : null}
      <ItemTable rows={rows} />
    </>
  );
}

function ItemTable({ rows }: { rows: ItemRecord[] }) {
  if (rows.length === 0) {
    return null;
  }
  const keys = Object.keys(rows[0]);
  return (
    <table>
      <thead>
        <tr>
          {keys.map((key) => (
            <th key={key}>{key}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => (
          <tr key={index}>
            {keys.map((key) => (
              <td key={key}>{qtyString(row[key])}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
