import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, Card, Space, Tooltip } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { asOfMeta, nextCursorOf, withQuery } from "../../api/envelope";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DataTable, type Column } from "../../shared/ui/DataTable";
import { errorBanner } from "../../shared/ui/errorBanner";
import { PageHead } from "../../shared/ui/PageHead";
import { QueryMeta } from "../../shared/ui/QueryMeta";
import { StatusBanner } from "../../shared/ui/StatusBanner";
import { WmsSearchForm } from "../../shared/ui/WmsSearchForm";
import { WmsToolbar } from "../../shared/ui/WmsToolbar";
import { useResource } from "../../shared/useResource";
import { useWorkspace } from "../../shell/WorkspaceContext";

const DEFAULT_COLUMNS: Column[] = [
  { key: "id", label: "标识", keys: ["id", "orderId", "planId", "jobId", "caseId"], kind: "id", copyKind: "单据" },
  { key: "status", label: "状态", keys: ["status", "state"], kind: "status" },
  { key: "skuId", label: "SKU", keys: ["skuId"], kind: "id", copyKind: "SKU" },
  { key: "qty", label: "数量", qty: true, keys: ["qty", "quantity", "onHandQty", "reservedQty"] },
  { key: "physicalStatus", label: "实物", keys: ["physicalStatus"], kind: "status" },
  { key: "stockSyncStatus", label: "库存同步", keys: ["stockSyncStatus"], kind: "status" }
];

export function DocumentListPage({
  title,
  sub,
  extra,
  paths,
  columns = DEFAULT_COLUMNS,
  empty,
  hrefFor,
  createLabel,
  createTitle,
  createHint,
  createScope,
  create,
  actions,
  queryKey = "q",
  cursorKey = "cursor",
  secondary
}: {
  title: string;
  sub: string;
  extra?: "tcc";
  paths: string[];
  columns?: Column[];
  empty: string;
  hrefFor?: (row: import("../../api/envelope").ItemRecord) => string | undefined;
  createLabel?: string;
  createTitle?: string;
  createHint?: string;
  createScope?: string | string[];
  create?: ReactNode;
  actions?: ReactNode;
  queryKey?: string;
  cursorKey?: string;
  secondary?: boolean;
}) {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const [search, setSearch] = useSearchParams();
  const query = search.get(queryKey) ?? "";
  const [draft, setDraft] = useState(query);
  const cursor = search.get(cursorKey) ?? "";
  useEffect(() => {
    setDraft(query);
  }, [query]);
  const resolved = (warehouseId ? paths : []).filter(Boolean).map((path) => withQuery(path, {
    cursor: cursor || undefined,
    limit: "20"
  }));
  const [tick, setTick] = useState(0);
  const { rows, payloads, error, loading } = useResource(token, resolved, tick);
  const meta = payloads[0] ? asOfMeta(payloads[0]) : null;
  const nextCursor = payloads[0] ? nextCursorOf(payloads[0]) : "";
  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) {
      return rows;
    }
    return rows.filter((row) => JSON.stringify(row).toLowerCase().includes(needle));
  }, [query, rows]);

  function patch(next: Record<string, string | undefined>) {
    const merged = new URLSearchParams(search);
    for (const [key, value] of Object.entries(next)) {
      if (value) {
        merged.set(key, value);
      } else {
        merged.delete(key);
      }
    }
    setSearch(merged, { replace: true });
  }

  const list = (
    <Card
      size={secondary ? "small" : "middle"}
      title={(
        <WmsToolbar
          title={secondary ? title : "业务列表"}
          count={loading ? undefined : String(visible.length)}
          extra={secondary ? actions : (
            <Tooltip title="刷新">
              <Button icon={<ReloadOutlined />} aria-label="刷新" onClick={() => setTick((current) => current + 1)} />
            </Tooltip>
          )}
        />
      )}
    >
      <DataTable
        caption={title}
        rows={visible}
        columns={columns}
        loading={loading}
        emptyText={query ? "当前筛选没有匹配。清除筛选后重试。" : empty}
        hrefFor={hrefFor}
        nextCursor={nextCursor}
        hasCursor={Boolean(cursor)}
        onFirstPage={() => patch({ [cursorKey]: undefined })}
        onNextPage={() => nextCursor ? patch({ [cursorKey]: nextCursor }) : undefined}
      />
    </Card>
  );

  if (secondary) {
    return (
      <Space orientation="vertical" size={12} style={{ display: "flex" }}>
        {error ? errorBanner(error) : null}
        {list}
      </Space>
    );
  }

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        eyebrow={warehouseName || warehouseId || "未选仓"}
        title={title}
        sub={warehouseId ? `${sub} · 当前仓 ${warehouseId}` : "尚未选仓，不会猜测仓库。"}
        extra={(
          <div className="list-toolbar">
            {create && createLabel ? (
              <CommandDrawer
                triggerLabel={createLabel}
                title={createTitle || createLabel}
                hint={createHint}
                requireScope={createScope}
                disabled={!token || !warehouseId || warehouseId === "_"}
                onSubmitted={() => {
                  setTick((current) => current + 1);
                  patch({ [cursorKey]: undefined });
                }}
              >
                {create}
              </CommandDrawer>
            ) : null}
            {actions}
          </div>
        )}
      />
      <QueryMeta
        warehouseId={warehouseId}
        warehouseName={warehouseName}
        asOf={meta?.asOf}
        lagSeconds={meta?.lagSeconds}
        stale={meta?.stale}
        rowCount={loading ? "读取中" : String(rows.length)}
      />
      {extra === "tcc" ? (
        <StatusBanner kind="tcc" title="跨仓分配请看各仓进度" detail="单仓 CONFIRMED 不是整单成功" />
      ) : null}
      {error ? errorBanner(error) : null}
      <WmsSearchForm
        value={draft}
        onChange={setDraft}
        onSearch={() => patch({ [queryKey]: draft.trim() || undefined, [cursorKey]: undefined })}
        onReset={() => {
          setDraft("");
          patch({ [queryKey]: undefined, [cursorKey]: undefined });
        }}
        placeholder="筛选已返回字段，查询后写入地址栏"
      />
      {list}
    </Space>
  );
}
