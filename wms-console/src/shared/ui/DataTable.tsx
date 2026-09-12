import { Table, Tag } from "antd";
import { field, qtyField, type ItemRecord } from "../../api/envelope";

export type Column = {
  key: string;
  label: string;
  qty?: boolean;
  keys?: string[];
};

function chipColor(value: string): string {
  const upper = value.toUpperCase();
  if (/(ACTIVE|OK|DONE|SUCCESS|CONFIRMED|APPLIED|AVAILABLE)/.test(upper)) {
    return "success";
  }
  if (/(FAIL|ERROR|REJECT|EXPIRED|HOLD|FORBIDDEN)/.test(upper)) {
    return "error";
  }
  if (/(PEND|WAIT|TRY|STALE|UNKNOWN|RESERVED)/.test(upper)) {
    return "warning";
  }
  return "processing";
}

export function DataTable({ rows, columns, loading, emptyText }: { rows: ItemRecord[]; columns: Column[]; loading?: boolean; emptyText?: string }) {
  return (
    <Table
      size="middle"
      loading={loading}
      pagination={rows.length > 12 ? { pageSize: 12, showSizeChanger: false } : false}
      rowKey={(row, index) => field(row, "id", "skuId") || String(index)}
      dataSource={rows}
      locale={{ emptyText: emptyText || "当前筛选没有行" }}
      columns={columns.map((column) => ({
        key: column.key,
        title: column.label,
        render: (_: unknown, row: ItemRecord) => {
          const keys = column.keys ?? [column.key];
          const value = column.qty ? qtyField(row, ...keys) : field(row, ...keys);
          const chip = column.key === "status" || column.key === "physicalStatus" || column.key === "stockSyncStatus";
          if (chip && value) {
            return <Tag color={chipColor(value)}>{value}</Tag>;
          }
          return value || "—";
        }
      }))}
    />
  );
}
