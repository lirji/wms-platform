import { field, type ItemRecord } from "../../api/envelope";

export function httpStatusOf(body: ItemRecord): number {
  const raw = body.__httpStatus;
  return typeof raw === "number" ? raw : 200;
}

export function isSyncPending(body: ItemRecord): boolean {
  const sync = field(body, "stockSyncStatus", "state");
  return httpStatusOf(body) === 202
    || sync === "PENDING"
    || sync === "ACCEPTED"
    || sync === "EXPORTING"
    || sync === "RETRY_ACCEPTED";
}

export function isTerminalSuccess(body: ItemRecord): boolean {
  if (isSyncPending(body)) {
    return false;
  }
  const sync = field(body, "stockSyncStatus");
  return !sync || sync === "APPLIED" || sync === "COMPLETE";
}
