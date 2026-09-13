export type StatusTone = "success" | "processing" | "waiting" | "error" | "closed";

/** 业务状态只走这五种语义，禁止页面自配色。 */
export function statusTone(value: string): StatusTone {
  const upper = value.toUpperCase();
  if (/(CANCEL|CLOSED|VOID|DELETED|INACTIVE|DISABLED|ABORTED)/.test(upper)) {
    return "closed";
  }
  if (/(FAIL|ERROR|REJECT|EXPIRED|HOLD|FORBIDDEN|CLAIMED|ISOLATED|SHORT|LOCK)/.test(upper)) {
    return "error";
  }
  if (/(PEND|WAIT|DRAFT|TRY|STALE|UNKNOWN|RESERVED|RETRY|EXPORT|PLANNED|ALLOCATED)/.test(upper)) {
    return "waiting";
  }
  if (/(ACTIVE|OK|DONE|SUCCESS|CONFIRMED|APPLIED|AVAILABLE|COMPLETE|RECEIVED|PUTAWAY|SHIPPED|PICKED)/.test(upper)) {
    return "success";
  }
  return "processing";
}

export function StatusChip({ value }: { value: string }) {
  return <span className={`wms-status-tag wms-status-tag--${statusTone(value)}`}>{value}</span>;
}
