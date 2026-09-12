import { field, qtyField, type ItemRecord } from "../../api/envelope";

export type Column = {
  key: string;
  label: string;
  qty?: boolean;
  keys?: string[];
};

function chipTone(value: string): string {
  const upper = value.toUpperCase();
  if (!value) {
    return "muted";
  }
  if (/(ACTIVE|OK|DONE|SUCCESS|CONFIRMED|APPLIED|AVAILABLE)/.test(upper)) {
    return "ok";
  }
  if (/(FAIL|ERROR|REJECT|EXPIRED|HOLD|FORBIDDEN)/.test(upper)) {
    return "err";
  }
  if (/(PEND|WAIT|TRY|STALE|UNKNOWN|RESERVED)/.test(upper)) {
    return "warn";
  }
  return "info";
}

export function DataTable({ rows, columns }: { rows: ItemRecord[]; columns: Column[] }) {
  if (rows.length === 0) {
    return null;
  }
  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key}>{column.label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={field(row, "id", "skuId") || String(index)}>
              {columns.map((column) => {
                const keys = column.keys ?? [column.key];
                const value = column.qty ? qtyField(row, ...keys) : field(row, ...keys);
                const chip = column.key === "status" || column.key === "physicalStatus" || column.key === "stockSyncStatus";
                return (
                  <td key={column.key} className={column.qty ? "is-qty" : undefined}>
                    {chip && value ? <span className={`chip chip-${chipTone(value)}`}>{value}</span> : value || "—"}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
