import { Children, Fragment, isValidElement, useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Button, Card, Descriptions, Space } from "antd";
import { hasScope } from "../../auth/can";
import { field, nestedRecords, type ItemRecord } from "../../api/envelope";
import { CommandDrawer } from "../command/CommandDrawer";
import { CopyId } from "../ui/CopyId";
import { DataTable, type Column } from "../ui/DataTable";
import { errorBanner } from "../ui/errorBanner";
import { PageHead } from "../ui/PageHead";
import { StatusBanner } from "../ui/StatusBanner";
import { useWorkspace } from "../../shell/WorkspaceContext";

const LINE_COLUMNS: Column[] = [
  { key: "id", label: "行", keys: ["id", "lineId", "orderLineId", "source_line_id", "external_line_id"], kind: "id", copyKind: "行" },
  { key: "skuId", label: "SKU", keys: ["skuId", "sku_id"], kind: "id", copyKind: "SKU" },
  { key: "status", label: "状态", keys: ["status", "state", "stock_sync_status"], kind: "status" },
  { key: "qty", label: "数量", qty: true, keys: ["expected_qty", "allocated_qty", "planned_qty", "requested_qty", "qty"] }
];

type CommandTab = { key: string; label: string; children: ReactNode };

type CommandColProps = {
  title: string;
  requireScope?: string | string[];
  children: ReactNode;
};

export function collectCommandTabs(node: ReactNode, scopes: string[] | undefined): CommandTab[] {
  return Children.toArray(node).flatMap((child) => {
    if (!isValidElement(child)) {
      return [];
    }
    if (child.type === Fragment) {
      return collectCommandTabs((child.props as { children?: ReactNode }).children, scopes);
    }
    if (child.type !== CommandCol) {
      return [];
    }
    const props = child.props as CommandColProps;
    if (!hasScope(scopes, props.requireScope)) {
      return [];
    }
    return [{ key: props.title, label: props.title, children: props.children }];
  });
}

function CommandTabs({ items }: { items: CommandTab[] }) {
  const [active, setActive] = useState(items[0]?.key ?? "");
  useEffect(() => {
    if (!items.some((item) => item.key === active)) {
      setActive(items[0]?.key ?? "");
    }
  }, [active, items]);
  const current = items.find((item) => item.key === active) ?? items[0];
  if (!current) {
    return null;
  }
  return (
    <div>
      <div role="tablist" aria-label="作业命令" style={{ display: "flex", flexWrap: "wrap", gap: 8, marginBottom: 16 }}>
        {items.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={item.key === current.key}
            className={item.key === current.key ? "ant-btn ant-btn-primary ant-btn-sm" : "ant-btn ant-btn-default ant-btn-sm"}
            style={{
              display: "inline-flex",
              alignItems: "center",
              height: 24,
              padding: "0 8px",
              border: item.key === current.key ? "1px solid #0f766e" : "1px solid #e2e8f0",
              borderRadius: 6,
              background: item.key === current.key ? "#0f766e" : "#fff",
              color: item.key === current.key ? "#fff" : "#0f172a",
              cursor: "pointer"
            }}
            onClick={() => setActive(item.key)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div role="tabpanel">{current.children}</div>
    </div>
  );
}

export function CommandCol({
  title,
  requireScope,
  children
}: CommandColProps) {
  return <div data-command={title} data-scope={String(requireScope ?? "")}>{children}</div>;
}

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
  const { scopes } = useWorkspace();
  const [tick, setTick] = useState(0);
  const lines = nestedRecords(record, ...lineKeys);
  const identifier = field(record, "orderId", "id", "fulfillmentId", "transferId");
  const tabs = useMemo(() => collectCommandTabs(commands, scopes), [commands, scopes]);
  void tick;

  return (
    <Space orientation="vertical" size={16} style={{ display: "flex" }}>
      <PageHead
        title={title}
        sub={sub}
        extra={(
          <Space>
            {tabs.length > 0 ? (
              <CommandDrawer
                triggerLabel="提交命令"
                title="作业命令"
                hint="一次只提交一个命令。202 不是成功。"
                width={480}
                onSubmitted={() => setTick((current) => current + 1)}
              >
                <CommandTabs items={tabs} />
              </CommandDrawer>
            ) : null}
            <Link to={backTo}><Button>{backLabel}</Button></Link>
          </Space>
        )}
      />
      {loading ? <StatusBanner kind="loading" title="加载单据，命令暂不可重复提交" /> : null}
      {error ? errorBanner(error) : null}
      {field(record, "stockSyncStatus") === "PENDING" ? (
        <StatusBanner
          kind="sync-pending"
          title="货已执行，库存待同步"
          operationId={field(record, "operationId", "commandId")}
          detail="禁止当作业务已成功，也不要新开实物命令。"
        />
      ) : null}
      {/HOLD|CLAIMED|TRANSFER/.test(field(record, "serialState", "qualityCode", "quality_code")) ? (
        <StatusBanner kind="serial-hold" title={field(record, "serialState", "qualityCode")} />
      ) : null}
      <Card title="单据">
        <Descriptions
          size="small"
          column={2}
          items={[
            { key: "status", label: "状态", children: field(record, "status", "state") || "—" },
            { key: "physical", label: "实物", children: field(record, "physicalStatus") || "—" },
            { key: "sync", label: "库存同步", children: field(record, "stockSyncStatus") || "—" },
            { key: "version", label: "版本", children: field(record, "version") || "—" },
            { key: "id", label: "标识", children: identifier ? <CopyId value={identifier} kind="单据" /> : "—" }
          ]}
        />
      </Card>
      <Card title="明细">
        <DataTable
          caption="单据明细"
          rows={lines}
          columns={[...LINE_COLUMNS, ...(extraColumns ?? [])]}
          emptyText="这张单还没有行"
        />
      </Card>
      {extra}
    </Space>
  );
}
