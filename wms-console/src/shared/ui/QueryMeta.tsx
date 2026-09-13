import { Descriptions } from "antd";
import { StatusChip } from "./StatusChip";

export function QueryMeta({
  warehouseId,
  warehouseName,
  asOf,
  lagSeconds,
  stale,
  rowCount
}: {
  warehouseId?: string;
  warehouseName?: string;
  asOf?: string;
  lagSeconds?: string;
  stale?: boolean;
  rowCount?: string;
}) {
  return (
    <Descriptions size="small" bordered column={rowCount ? 4 : 3} className="query-meta">
      {warehouseId ? <Descriptions.Item label="当前仓">{warehouseName || warehouseId}</Descriptions.Item> : null}
      <Descriptions.Item label="asOf">
        {stale ? <StatusChip value="STALE" /> : (asOf || "接口未返回")}
      </Descriptions.Item>
      <Descriptions.Item label="lagSeconds">{lagSeconds || "—"}</Descriptions.Item>
      {rowCount !== undefined ? <Descriptions.Item label="行数">{rowCount}</Descriptions.Item> : null}
    </Descriptions>
  );
}
