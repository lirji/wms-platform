import { Dispatch, FormEvent, SetStateAction, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, ApiError, rememberKey } from "../api/client";
import { StatusBanner } from "../components/StatusBanner";

type PageProps = {
  token?: string;
  warehouseId: string;
  setWarehouseId: Dispatch<SetStateAction<string>>;
};

type ItemRecord = Record<string, unknown>;

const MODULES = [
  { to: "/masterdata", title: "商品 / 库位", hint: "SKU 策略与库位状态，只读主数据" },
  { to: "/inbound", title: "入库工作台", hint: "收货、质检、上架单据" },
  { to: "/inventory", title: "库存台账", hint: "余额与 asOf，数量按字符串展示" },
  { to: "/outbound", title: "出库履约", hint: "跨仓进度以各仓状态为准" },
  { to: "/transfers", title: "调拨", hint: "源仓发出与目的接收" },
  { to: "/counts", title: "盘点", hint: "冻结、点数与调整" },
  { to: "/jobs", title: "任务 / 设备", hint: "作业分片与异常回执" },
  { to: "/recon", title: "对账差异", hint: "按 cutoff 查询，不写死差异" },
  { to: "/pda", title: "PDA 收货", hint: "扫码枪连续输入，回车提交" }
];

function asItems(payload: unknown): ItemRecord[] {
  if (payload && typeof payload === "object" && "items" in payload && Array.isArray((payload as { items: unknown }).items)) {
    return (payload as { items: ItemRecord[] }).items;
  }
  return [];
}

function qtyString(value: unknown): string {
  if (value == null) {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

function errorBanner(error: unknown) {
  if (error instanceof TypeError) {
    return <StatusBanner kind="error" title="无法连接对应服务" detail="请确认 inbound / outbound / inventory / fulfillment 已启动" />;
  }
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
  if (apiError?.status && apiError.status >= 500) {
    return <StatusBanner kind="error" title="对应服务暂时不可用" detail={`${apiError.status} ${apiError.message}`} />;
  }
  return <StatusBanner kind="error" title={apiError?.message ?? "请求失败"} />;
}

function PageHead({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        <p className="page-sub">{sub}</p>
      </div>
    </div>
  );
}

export function HomePage({ token, warehouseId }: PageProps) {
  const [inventory, setInventory] = useState<ItemRecord | null>(null);
  const [error, setError] = useState<unknown>();
  useEffect(() => {
    if (!token || !warehouseId) {
      setInventory(null);
      return;
    }
    api(`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`, token)
      .then((body) => {
        setInventory(body as ItemRecord);
        setError(undefined);
      })
      .catch(setError);
  }, [token, warehouseId]);
  const lag = inventory && typeof inventory.lagSeconds === "number" ? inventory.lagSeconds : null;
  return (
    <section>
      <PageHead title="仓库工作台" sub="先选当前仓，再进入作业。列表与数量都来自接口，不在页面写死。" />
      {error ? errorBanner(error) : null}
      {!warehouseId ? (
        <StatusBanner kind="empty" title="还没有可作业的仓库" detail="顶栏会列出当前令牌允许的仓；服务不可达时不会伪装成没有权限。" />
      ) : null}
      {inventory ? (
        <StatusBanner
          kind={lag !== null && lag > 30 ? "stale" : "success"}
          title={`当前仓 ${warehouseId} · asOf ${String(inventory.asOf ?? "未知")}`}
          detail={lag === null ? "延迟未知，写入仍由服务端重校验" : `lagSeconds=${lag}，陈旧时请刷新后等待`}
        />
      ) : null}
      <div className="module-grid">
        {MODULES.map((item) => (
          <Link key={item.to} className="module-card" to={item.to}>
            <strong>{item.title}</strong>
            <span>{item.hint}</span>
          </Link>
        ))}
      </div>
    </section>
  );
}

export function MasterdataPage({ token, warehouseId }: PageProps) {
  return (
    <section>
      <PageHead title="商品 / 库位" sub="主数据只读。缺仓时先回工作台确认令牌仓范围。" />
      <ResourceList
        token={token}
        empty="当前过滤条件下没有主数据"
        paths={["/api/wms/v1/skus", warehouseId ? `/api/wms/v1/warehouses/${warehouseId}/locations` : ""]}
      />
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
      <PageHead title="库存台账" sub="数量按字符串展示，不在浏览器做发运量运算。" />
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
      <PageHead title={title} sub={warehouseId ? `当前仓 ${warehouseId}` : "尚未选仓，单据列表不会猜测仓库。"} />
      {extra ? <StatusBanner kind="tcc" title={extra} /> : null}
      <ResourceList
        token={token}
        empty={`当前仓 ${warehouseId || "(未选)"} 没有单据`}
        paths={[path.includes("/warehouses//") || path.endsWith("warehouseId=") ? "" : path]}
      />
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
      <PageHead title="对账差异" sub="按 cutoff 查询服务端差异，页面不预置差异列表。" />
      <form className="panel" onSubmit={load}>
        <label>
          cutoffId
          <input value={cutoffId} onChange={(event) => setCutoffId(event.target.value)} required />
        </label>
        <button className="btn btn-primary" type="submit" disabled={busy || !cutoffId}>{busy ? "查询中" : "加载差异"}</button>
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
      <PageHead title="PDA 收货" sub="扫码枪连续输入，成功失败同时用文字说明，不只靠颜色。" />
      <div className="pda-card">
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
          <button className="btn btn-primary" type="submit">回车提交</button>
        </form>
      </div>
    </section>
  );
}

function ResourceList({ token, paths, empty }: { token?: string; paths: string[]; empty: string }) {
  const [rows, setRows] = useState<ItemRecord[]>([]);
  const [error, setError] = useState<unknown>();
  const [loading, setLoading] = useState(true);
  const usable = paths.filter(Boolean);
  const joined = usable.join("|");
  useEffect(() => {
    if (!token || usable.length === 0) {
      setLoading(false);
      return;
    }
    setLoading(true);
    Promise.all(usable.map((path) => api(path, token)))
      .then((bodies) => setRows(bodies.flatMap((body) => asItems(body))))
      .catch(setError)
      .finally(() => setLoading(false));
  }, [token, joined]);
  return (
    <>
      {loading ? <StatusBanner kind="loading" title="加载中，请勿重复提交" /> : null}
      {error ? errorBanner(error) : null}
      {!loading && !error && rows.length === 0 ? <StatusBanner kind="empty" title={empty} /> : null}
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
    <div className="table-wrap">
      <table className="data-table">
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
    </div>
  );
}
