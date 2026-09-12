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
    <dl className="query-meta">
      {warehouseId ? (
        <div>
          <dt>当前仓</dt>
          <dd>{warehouseName || warehouseId}</dd>
        </div>
      ) : null}
      <div>
        <dt>asOf</dt>
        <dd className={stale ? "is-stale" : undefined}>{asOf || "接口未返回"}</dd>
      </div>
      <div>
        <dt>lagSeconds</dt>
        <dd>{lagSeconds || "—"}</dd>
      </div>
      {rowCount !== undefined ? (
        <div>
          <dt>行数</dt>
          <dd>{rowCount}</dd>
        </div>
      ) : null}
    </dl>
  );
}
