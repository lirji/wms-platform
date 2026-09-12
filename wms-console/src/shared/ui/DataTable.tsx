import { field, qtyField, type ItemRecord } from "../../api/envelope";

export type Column = {
  key: string;
  label: string;
  qty?: boolean;
  keys?: string[];
};

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
                return <td key={column.key}>{value}</td>;
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
