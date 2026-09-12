import { asOfMeta } from "../../api/envelope";
import { DataTable, type Column } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
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
  const { token, warehouseId } = useWorkspace();
  const { rows, payloads, error, loading } = useResource(token, warehouseId ? paths : []);
  const meta = payloads[0] ? asOfMeta(payloads[0]) : null;
  return (
    <section>
      <PageHead title={title} sub={warehouseId ? `${sub} · 当前仓 ${warehouseId}` : "尚未选仓，不会猜测仓库。"} />
      {extra === "tcc" ? (
        <StatusBanner kind="tcc" title="跨仓分配请看各仓进度" detail="单仓 CONFIRMED 不是整单成功" />
      ) : null}
      {loading ? <StatusBanner kind="loading" title="加载中，请勿重复提交" /> : null}
      {error ? errorBanner(error) : null}
      {meta?.asOf ? (
        <StatusBanner
          kind={meta.stale ? "stale" : "success"}
          title={`asOf ${meta.asOf}`}
          detail={meta.lagSeconds ? `lagSeconds=${meta.lagSeconds}，写入由服务端重校验` : "延迟未知"}
        />
      ) : null}
      {!loading && !error && rows.length === 0 ? <StatusBanner kind="empty" title={empty} /> : null}
      <DataTable rows={rows} columns={columns} />
    </section>
  );
}
