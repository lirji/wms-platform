export type ItemRecord = Record<string, unknown>;

export function pageItems(payload: unknown): ItemRecord[] {
  if (payload && typeof payload === "object" && "items" in payload && Array.isArray((payload as { items: unknown }).items)) {
    return (payload as { items: ItemRecord[] }).items;
  }
  return [];
}

export function field(row: ItemRecord, ...keys: string[]): string {
  for (const key of keys) {
    const value = row[key];
    if (value == null || value === "") {
      continue;
    }
    if (typeof value === "object") {
      return JSON.stringify(value);
    }
    return String(value);
  }
  return "";
}

export function qtyField(row: ItemRecord, ...keys: string[]): string {
  return field(row, ...keys);
}

export function asRecord(payload: unknown): ItemRecord {
  return payload && typeof payload === "object" ? payload as ItemRecord : {};
}

export function nestedRecords(payload: unknown, ...keys: string[]): ItemRecord[] {
  const record = asRecord(payload);
  for (const key of keys) {
    const value = record[key];
    if (Array.isArray(value)) {
      return value as ItemRecord[];
    }
  }
  return [];
}

export function recordId(row: ItemRecord, ...keys: string[]): string {
  return field(row, ...keys, "id", "orderId", "fulfillmentId", "transferId", "planId", "jobId", "caseId");
}

export function asOfMeta(payload: unknown): { asOf: string; lagSeconds: string; stale: boolean } {
  const record = payload && typeof payload === "object" ? payload as ItemRecord : {};
  const lag = Number(record.lagSeconds ?? 0);
  return {
    asOf: field(record, "asOf"),
    lagSeconds: field(record, "lagSeconds"),
    stale: Number.isFinite(lag) && lag > 30
  };
}
