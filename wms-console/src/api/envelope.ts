export type ItemRecord = Record<string, unknown>;

export function pageItems(payload: unknown): ItemRecord[] {
  if (payload && typeof payload === "object" && "items" in payload && Array.isArray((payload as { items: unknown }).items)) {
    return (payload as { items: ItemRecord[] }).items;
  }
  return [];
}

function snakeOf(key: string): string {
  if (!/[A-Z]/.test(key)) {
    return key;
  }
  return key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
}

function readField(row: ItemRecord, key: string): string {
  const value = row[key];
  if (value == null || value === "") {
    return "";
  }
  if (typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

export function field(row: ItemRecord, ...keys: string[]): string {
  for (const key of keys) {
    const direct = readField(row, key);
    if (direct) {
      return direct;
    }
    const snake = snakeOf(key);
    if (snake !== key) {
      const aliased = readField(row, snake);
      if (aliased) {
        return aliased;
      }
    }
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

export function nextCursorOf(payload: unknown): string {
  return field(asRecord(payload), "nextCursor");
}

export function withQuery(path: string, params: Record<string, string | undefined>): string {
  const cut = path.indexOf("?");
  const base = cut >= 0 ? path.slice(0, cut) : path;
  const search = new URLSearchParams(cut >= 0 ? path.slice(cut + 1) : "");
  for (const [key, value] of Object.entries(params)) {
    if (value) {
      search.set(key, value);
    } else {
      search.delete(key);
    }
  }
  const query = search.toString();
  return query ? `${base}?${query}` : base;
}

export function asOfMeta(payload: unknown): { asOf: string; lagSeconds: string; stale: boolean } {
  const record = payload && typeof payload === "object" ? payload as ItemRecord : {};
  const lagSeconds = field(record, "lagSeconds");
  const lag = Number(lagSeconds || 0);
  return {
    asOf: field(record, "asOf"),
    lagSeconds,
    stale: Number.isFinite(lag) && lag > 30
  };
}
