import { useMemo, useState, type ReactNode } from "react";
import { Card, Flex, Input, Space } from "antd";
import { asOfMeta } from "../../api/envelope";
import { CommandDrawer } from "../../shared/command/CommandDrawer";
import { DataTable, type Column } from "../../shared/ui/DataTable";
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
  empty,
  hrefFor,
  createLabel,
  createTitle,
  createHint,
  create
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
  create?: ReactNode;
}) {
  const { token, warehouseId, warehouseName } = useWorkspace();
  const [tick, setTick] = useState(0);
  const { rows, payloads, error, loading } = useResource(token, warehouseId ? paths : [], tick);
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
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        eyebrow={warehouseName || warehouseId || "未选仓"}
        title={title}
        sub={warehouseId ? `${sub} · 当前仓 ${warehouseId}` : "尚未选仓，不会猜测仓库。"}
        extra={(
          <Flex align="center" gap={12} wrap="wrap">
            <QueryMeta
              warehouseId={warehouseId}
              warehouseName={warehouseName}
              asOf={meta?.asOf}
              lagSeconds={meta?.lagSeconds}
              stale={meta?.stale}
              rowCount={loading ? "读取中" : String(rows.length)}
            />
            {create && createLabel ? (
              <CommandDrawer
                triggerLabel={createLabel}
                title={createTitle || createLabel}
                hint={createHint}
                disabled={!token || !warehouseId || warehouseId === "_"}
                onSubmitted={() => setTick((current) => current + 1)}
              >
                {create}
              </CommandDrawer>
            ) : null}
          </Flex>
        )}
      />
      {extra === "tcc" ? (
        <StatusBanner kind="tcc" title="跨仓分配请看各仓进度" detail="单仓 CONFIRMED 不是整单成功" />
      ) : null}
      {error ? errorBanner(error) : null}
      <Card
        title="业务列表"
        extra={(
          <Input.Search
            allowClear
            style={{ width: 280 }}
            placeholder="筛选已返回字段，不请求新数据"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        )}
      >
        <DataTable rows={visible} columns={columns} loading={loading} emptyText={empty} hrefFor={hrefFor} />
      </Card>
    </Space>
  );
}
