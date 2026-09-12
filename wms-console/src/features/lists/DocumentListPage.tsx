import { useMemo, useState } from "react";
import { asOfMeta } from "../../api/envelope";
import { DataTable, type Column } from "../../shared/ui/DataTable";
import { EmptyState } from "../../shared/ui/EmptyState";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { QueryMeta } from "../../shared/ui/QueryMeta";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { useResource } from "../../shared/useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

const DEFAULT_COLUMNS: Column[] = [
  { key: "id", label: "标识", keys: ["id", "orderId", "planId", "jobId", "caseId"] },
  { key: "status", label: "状态", keys: ["status", "state"] },
  { key: "skuId", label: "SKU", keys: ["skuId"] },
  { key: "qty", label: "数量", qty: true, keys: ["qty", "quantity", "onHandQty", "reservedQty"] },
  { key: "physicalStatus", label: "实物", keys: ["physicalStatus"] },
  { key: "stockSyncStatus", label: "库存同步", keys: ["stockSyncStatus"] }
];

export function DocumentListPage({
  title,
  sub,
  extra,
  paths,
  columns = DEFAULT_COLUMNS,
  empty
}: {
  title: string;
  sub: string;
  extra?: "tcc";
  paths: string[];
  columns?: Column[];
  empty: string;
}) {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const { rows, payloads, error, loading } = useResource(token, warehouseId ? paths : []);
  const [query, setQuery] = useState("");
  const meta = payloads[0] ? asOfMeta(payloads[0]) : null;
  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) {
      return rows;
    }
    return rows.filter((row) => JSON.stringify(row).toLowerCase().includes(needle));
  }, [query, rows]);

  return (
    <section className="page">
      <PageHead
        eyebrow={warehouseName || warehouseId || "未选仓"}
        title={title}
        sub={warehouseId ? `${sub} · 当前仓 ${warehouseId}` : "尚未选仓，不会猜测仓库。"}
        extra={(
          <QueryMeta
            warehouseId={warehouseId}
            warehouseName={warehouseName}
            asOf={meta?.asOf}
            lagSeconds={meta?.lagSeconds}
            stale={meta?.stale}
            rowCount={loading ? "读取中" : String(rows.length)}
          />
        )}
      />
      {extra === "tcc" ? (
        <StatusBanner kind="tcc" title="跨仓分配请看各仓进度" detail="单仓 CONFIRMED 不是整单成功" />
      ) : null}
      {error ? errorBanner(error) : null}
      <div className="panel">
        <div className="toolbar">
          <div>
            <strong>业务列表</strong>
            <span className="toolbar-hint">{loading ? "正在从对应服务读取" : `接口返回 ${rows.length} 条，当前显示 ${visible.length} 条`}</span>
          </div>
          <label className="toolbar-search">
            <span>筛选已加载行</span>
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="按已返回字段筛选，不请求新数据" />
          </label>
        </div>
        {loading ? <StatusBanner kind="loading" title="加载中，请勿重复提交" /> : null}
        {!loading && !error && rows.length === 0 ? (
          <EmptyState title={empty} detail="空态来自接口，不在页面预置单据或库存。" />
        ) : null}
        {!loading && !error && rows.length > 0 && visible.length === 0 ? (
          <EmptyState title="没有匹配当前筛选的行" detail="清空筛选后重新查看接口返回的全部行。" />
        ) : null}
        <DataTable rows={visible} columns={columns} />
      </div>
    </section>
  );
}
