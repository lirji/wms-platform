import { Link } from "react-router-dom";
import { Skeleton, Table } from "antd";
import { field, qtyField, recordId, type ItemRecord } from "../../api/envelope";
import { CopyId } from "./CopyId";
import { EmptyState } from "./EmptyState";
import { ListPager } from "./ListPager";
import { StatusChip } from "./StatusChip";

export type Column = {
  key: string;
  label: string;
  qty?: boolean;
  keys?: string[];
  kind?: "id" | "status" | "qty" | "name" | "text";
  copyKind?: string;
};

function kindOf(column: Column): NonNullable<Column["kind"]> {
  if (column.kind) {
    return column.kind;
  }
  if (column.qty || column.key === "qty") {
    return "qty";
  }
  if (column.key === "id" || column.key === "skuId") {
    return "id";
  }
  if (column.key === "status" || column.key === "physicalStatus" || column.key === "stockSyncStatus") {
    return "status";
  }
  return "name";
}


const WIDTH: Record<ReturnType<typeof kindOf>, number> = {
  id: 180,
  status: 128,
  qty: 112,
  name: 168,
  text: 200
};

export function DataTable({
  rows,
  columns,
  loading,
  emptyText,
  hrefFor,
  caption,
  nextCursor,
  hasCursor,
  onFirstPage,
  onNextPage
}: {
  rows: ItemRecord[];
  columns: Column[];
  loading?: boolean;
  emptyText?: string;
  hrefFor?: (row: ItemRecord) => string | undefined;
  caption?: string;
  nextCursor?: string;
  hasCursor?: boolean;
  onFirstPage?: () => void;
  onNextPage?: () => void;
}) {
  const skeletonRows = loading && rows.length === 0
    ? [{ id: "sk-1" }, { id: "sk-2" }, { id: "sk-3" }] as ItemRecord[]
    : rows;
  return (
    <>
      <Table
        size="small"
        sticky
        tableLayout="fixed"
        scroll={{ x: "max-content" }}
        pagination={false}
        rowKey={(row) => recordId(row) || field(row, "skuId", "lineId") || JSON.stringify(row)}
        dataSource={skeletonRows}
        locale={{ emptyText: <EmptyState title={emptyText || "暂无数据"} /> }}
        aria-label={caption}
        columns={columns.map((column) => {
          const kind = kindOf(column);
          return {
            key: column.key,
            title: column.label,
            width: WIDTH[kind],
            align: kind === "qty" ? "right" as const : "left" as const,
            ellipsis: kind === "id" || kind === "name",
            fixed: kind === "id" && column.key === "id" ? "left" as const : undefined,
            render: (_: unknown, row: ItemRecord) => {
              if (loading && rows.length === 0) {
                return <Skeleton title={false} paragraph={{ rows: 1, width: "80%" }} active />;
              }
              const keys = column.keys ?? [column.key];
              const value = column.qty ? qtyField(row, ...keys) : field(row, ...keys);
              const href = column.key === "id" && hrefFor ? hrefFor(row) : undefined;
              if (href) {
                return (
                  <span style={{ display: "inline-flex", alignItems: "center", gap: 4, minWidth: 0 }}>
                    <Link to={href} style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {value || "打开单据"}
                    </Link>
                    {value ? <CopyId value={value} kind={column.copyKind || "单据"} hideValue /> : null}
                  </span>
                );
              }
              if (kind === "id" && value) {
                return <CopyId value={value} kind={column.copyKind || column.label} />;
              }
              if ((kind === "status") && value) {
                return <StatusChip value={value} />;
              }
              if (kind === "qty") {
                return <span style={{ fontVariantNumeric: "tabular-nums" }}>{value || "—"}</span>;
              }
              return value || "—";
            }
          };
        })}
      />
      {onFirstPage || onNextPage ? (
        <ListPager
          countLabel={`本页 ${rows.length} 条`}
          prevDisabled={!hasCursor}
          nextDisabled={!nextCursor}
          onPrev={onFirstPage}
          onNext={onNextPage}
        />
      ) : null}
    </>
  );
}
