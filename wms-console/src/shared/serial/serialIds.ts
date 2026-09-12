/** 与契约 Serial*Observation / SerialStockSelection 对齐：ROOT 大写、去重、最多 200。 */

export const SERIAL_SCHEMA_VERSION = 1;
export const SERIAL_MAX = 200;

export type SerialParse = {
  serialIds: string[];
  error?: string;
};

export function parseSerialIds(text: string, options?: { allowEmpty?: boolean }): SerialParse {
  const seen = new Set<string>();
  const serialIds: string[] = [];
  for (const raw of text.split(/[\n,;]+/)) {
    const serial = raw.trim().toLocaleUpperCase("en-US");
    if (!serial) {
      continue;
    }
    if (serial.length > 64) {
      return { serialIds: [], error: `序列号超过 64 字符：${serial.slice(0, 16)}…` };
    }
    if (seen.has(serial)) {
      return { serialIds: [], error: `序列号重复：${serial}` };
    }
    seen.add(serial);
    serialIds.push(serial);
  }
  if (serialIds.length > SERIAL_MAX) {
    return { serialIds: [], error: `一次最多 ${SERIAL_MAX} 个身份，当前 ${serialIds.length}` };
  }
  if (serialIds.length === 0 && !options?.allowEmpty) {
    return { serialIds: [] };
  }
  return { serialIds };
}

export function countText(serialIds: string[]): string {
  return String(serialIds.length);
}

export function receiptObservation(text: string): { schemaVersion: 1; serialIds: string[] } | undefined {
  const parsed = parseSerialIds(text);
  if (parsed.error) {
    throw new Error(parsed.error);
  }
  if (parsed.serialIds.length === 0) {
    return undefined;
  }
  return { schemaVersion: SERIAL_SCHEMA_VERSION, serialIds: parsed.serialIds };
}

export function stockSelection(text: string): { schemaVersion: 1; serialIds: string[] } | undefined {
  return receiptObservation(text);
}

export function qualityObservation(acceptedText: string, rejectedText: string):
  | { schemaVersion: 1; acceptedSerials: string[]; rejectedSerials: string[] }
  | undefined {
  const accepted = parseSerialIds(acceptedText);
  const rejected = parseSerialIds(rejectedText);
  if (accepted.error) {
    throw new Error(accepted.error);
  }
  if (rejected.error) {
    throw new Error(rejected.error);
  }
  if (accepted.serialIds.length === 0 && rejected.serialIds.length === 0) {
    return undefined;
  }
  const overlap = accepted.serialIds.find((serial) => rejected.serialIds.includes(serial));
  if (overlap) {
    throw new Error(`合格与不合格清单互斥，重复：${overlap}`);
  }
  if (accepted.serialIds.length + rejected.serialIds.length > SERIAL_MAX) {
    throw new Error(`合格与不合格合计最多 ${SERIAL_MAX} 个身份`);
  }
  return {
    schemaVersion: SERIAL_SCHEMA_VERSION,
    acceptedSerials: accepted.serialIds,
    rejectedSerials: rejected.serialIds
  };
}

export function countObservation(text: string, allMissing: boolean):
  | { schemaVersion: 1; serialIds: string[] }
  | undefined {
  if (!allMissing && !text.trim()) {
    return undefined;
  }
  const parsed = parseSerialIds(text, { allowEmpty: true });
  if (parsed.error) {
    throw new Error(parsed.error);
  }
  if (allMissing && parsed.serialIds.length > 0) {
    throw new Error("全部未见时不要再填写实见身份");
  }
  return { schemaVersion: SERIAL_SCHEMA_VERSION, serialIds: allMissing ? [] : parsed.serialIds };
}
