import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Button, Card, Space } from "antd";
import { field, nestedRecords, type ItemRecord } from "../../api/envelope";
import { CommandDrawer } from "../command/CommandDrawer";
import { DataTable, type Column } from "../ui/DataTable";
import { errorBanner } from "../ui/errorBanner";
import { PageHead } from "../ui/PageHead";
import { StatusBanner } from "../ui/StatusBanner";

const LINE_COLUMNS: Column[] = [
  { key: "id", label: "行", keys: ["id", "lineId", "orderLineId", "source_line_id", "external_line_id"] },
  { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"] },
  { key: "status", label: "状态", keys: ["status", "state", "stock_sync_status"] },
  { key: "qty", label: "数量", qty: true, keys: ["expected_qty", "allocated_qty", "planned_qty", "requested_qty", "qty"] }
];

export function DocumentWorkbench({
  backTo,
  backLabel,
  title,
  sub,
  loading,
  error,
  record,
  lineKeys = ["lines"],
  extraColumns,
  commands,
  extra
}: {
  backTo: string;
  backLabel: string;
  title: string;
  sub: string;
  loading?: boolean;
  error?: unknown;
  record: ItemRecord;
  lineKeys?: string[];
  extraColumns?: Column[];
  commands: ReactNode;
  extra?: ReactNode;
}) {
  const [tick, setTick] = useState(0);
  const lines = nestedRecords(record, ...lineKeys);
  void tick;
  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        title={title}
        sub={sub}
        extra={(
          <Space>
            <CommandDrawer
              triggerLabel="提交命令"
              title="作业命令"
              hint="一次只提交一个命令。202 不是成功。"
              width={480}
              onSubmitted={() => setTick((current) => current + 1)}
            >
              <Space orientation="vertical" size={12} style={{ display: "flex" }}>
                {commands}
              </Space>
            </CommandDrawer>
            <Link to={backTo}><Button>{backLabel}</Button></Link>
          </Space>
        )}
      />
      {loading ? <StatusBanner kind="loading" title="加载单据，命令暂不可重复提交" /> : null}
      {error ? errorBanner(error) : null}
      <Card title="单据">
        <p>状态 {field(record, "status", "state") || "—"} · 版本 {field(record, "version") || "—"}</p>
        <p>标识 {field(record, "orderId", "id", "fulfillmentId", "transferId") || "—"}</p>
      </Card>
      <Card title="明细">
        <DataTable rows={lines} columns={[...LINE_COLUMNS, ...(extraColumns ?? [])]} emptyText="这张单还没有行" />
      </Card>
      {extra}
    </Space>
  );
}

export function CommandCol({ children }: { children: ReactNode }) {
  return <div>{children}</div>;
}
