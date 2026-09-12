import { Link } from "react-router-dom";
import { asOfMeta } from "../../api/envelope";
import { EmptyState } from "../../shared/ui/EmptyState";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { QueryMeta } from "../../shared/ui/QueryMeta";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { countText, useSettledResource } from "../../shared/useSettledResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

const MODULES = [
  { to: "catalog", title: "商品 / 库位", hint: "SKU 策略、单位与库位状态，只读", countKey: "skus" },
  { to: "inbound", title: "入库作业", hint: "收货、质检、上架单据", countKey: "inbound" },
  { to: "stock", title: "库存台账", hint: "余额与 asOf，数量按字符串展示", countKey: "stock" },
  { to: "fulfillment", title: "出库履约", hint: "全局单与本仓出库进度", countKey: "outbound" },
  { to: "transfers", title: "调拨", hint: "源仓发出与目的接收", countKey: "transfers" },
  { to: "counts", title: "盘点", hint: "冻结、点数与调整", countKey: "counts" },
  { to: "jobs", title: "任务 / 设备", hint: "作业运行与分片回执", countKey: "jobs" },
  { to: "recon", title: "对账差异", hint: "按 cutoff 查询，不预置差异", countKey: "" }
];

export function HomePage() {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const ready = Boolean(warehouseId && warehouseId !== "_");
  const { buckets, loading } = useSettledResource(token, ready ? [
    "/api/wms/v1/warehouses",
    "/api/wms/v1/skus",
    `/api/wms/v1/warehouses/${warehouseId}/locations`,
    `/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`,
    `/api/wms/v1/warehouses/${warehouseId}/inbound-orders`,
    `/api/wms/v1/warehouses/${warehouseId}/outbound-orders`,
    "/api/wms/v1/fulfillments",
    "/api/wms/v1/transfers",
    `/api/wms/v1/warehouses/${warehouseId}/count-plans`,
    `/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`
  ] : []);
  const by = Object.fromEntries(buckets.map((bucket) => [bucket.path, bucket]));
  const stock = by[`/api/wms/v1/inventory?warehouseIds=${encodeURIComponent(warehouseId)}`];
  const inbound = by[`/api/wms/v1/warehouses/${warehouseId}/inbound-orders`];
  const outbound = by[`/api/wms/v1/warehouses/${warehouseId}/outbound-orders`];
  const jobs = by[`/api/wms/v1/jobs?warehouseId=${encodeURIComponent(warehouseId)}`];
  const counts = {
    warehouses: countText(by["/api/wms/v1/warehouses"]),
    skus: countText(by["/api/wms/v1/skus"]),
    locations: countText(by[`/api/wms/v1/warehouses/${warehouseId}/locations`]),
    stock: countText(stock),
    inbound: countText(inbound),
    outbound: countText(outbound),
    fulfillments: countText(by["/api/wms/v1/fulfillments"]),
    transfers: countText(by["/api/wms/v1/transfers"]),
    counts: countText(by[`/api/wms/v1/warehouses/${warehouseId}/count-plans`]),
    jobs: countText(jobs)
  };
  const meta = stock?.payload ? asOfMeta(stock.payload) : null;
  const firstError = buckets.find((bucket) => bucket.error)?.error;
  const notes = [
    inbound && !inbound.error && inbound.rows.length === 0 ? "本仓暂无入库单，收货入口可从 PDA 打开。" : "",
    stock && !stock.error && stock.rows.length === 0 ? "库存投影当前没有行，不把健康检查当成有货。" : "",
    jobs && !jobs.error && jobs.rows.length === 0 ? "没有正在跟踪的任务运行。" : "",
    meta?.stale ? `库存查询陈旧，lagSeconds=${meta.lagSeconds}，写入仍由服务端重校验。` : ""
  ].filter(Boolean);

  return (
    <section className="page">
      <PageHead
        eyebrow="作业总览"
        title="仓库工作台"
        sub="先确认当前仓，再进入作业。卡片上的数字是接口返回的行数，不是页面写死的库存或单据。"
        extra={(
          <QueryMeta
            warehouseId={warehouseId}
            warehouseName={warehouseName}
            asOf={meta?.asOf}
            lagSeconds={meta?.lagSeconds}
            stale={meta?.stale}
          />
        )}
      />
      {!ready ? <StatusBanner kind="empty" title="还没有可作业的仓库" detail="顶栏会列出当前令牌允许的仓；服务不可达时不会伪装成没有权限。" /> : null}
      {firstError ? errorBanner(firstError) : null}
      <div className="kpi-grid">
        {[
          { label: "可访问仓", value: loading ? "…" : counts.warehouses, hint: "当前令牌可见" },
          { label: "SKU", value: loading ? "…" : counts.skus, hint: "企业主数据" },
          { label: "本仓库位", value: loading ? "…" : counts.locations, hint: "当前作业仓" },
          { label: "库存行", value: loading ? "…" : counts.stock, hint: "投影行，非合计数量" },
          { label: "入库单", value: loading ? "…" : counts.inbound, hint: "本仓收货单据" },
          { label: "出库单", value: loading ? "…" : counts.outbound, hint: "本仓出库单据" }
        ].map((item) => (
          <article key={item.label} className="kpi-card">
            <span>{item.label}</span>
            <strong>{item.value}</strong>
            <small>{item.hint}</small>
          </article>
        ))}
      </div>
      <div className="home-split">
        <div className="panel">
          <div className="toolbar">
            <div>
              <strong>作业入口</strong>
              <span className="toolbar-hint">角标来自对应列表接口；打不开的服务显示「不可用」</span>
            </div>
          </div>
          <div className="module-grid">
            {MODULES.map((item) => {
              const href = ready ? `/w/${warehouseId}/${item.to}` : ".";
              const count = item.countKey ? counts[item.countKey as keyof typeof counts] : "按 cutoff";
              return (
                <Link key={item.to} className="module-card" to={href}>
                  <span className="module-count">{loading ? "…" : count}</span>
                  <strong>{item.title}</strong>
                  <span>{item.hint}</span>
                </Link>
              );
            })}
          </div>
        </div>
        <div className="panel">
          <div className="toolbar">
            <div>
              <strong>本仓注意</strong>
              <span className="toolbar-hint">只陈述接口事实，不编造待办</span>
            </div>
          </div>
          {loading ? <StatusBanner kind="loading" title="正在汇总本仓只读指标" /> : null}
          {!loading && notes.length === 0 ? (
            <EmptyState title="没有需要单独提示的查询异常" detail="单据与库存行数见左侧入口，不在这里写死待办清单。" />
          ) : null}
          {!loading && notes.length > 0 ? (
            <ul className="note-list">
              {notes.map((note) => <li key={note}>{note}</li>)}
            </ul>
          ) : null}
        </div>
      </div>
    </section>
  );
}
