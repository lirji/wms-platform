export type ApiError = {
  status: number;
  code?: string;
  message: string;
  retryable?: boolean;
  body?: unknown;
};

export type RequestOptions = {
  method?: string;
  body?: unknown;
  idempotencyKey?: string;
  warehouseId?: string;
};

const PREFIX = {
  inbound: "/inbound-api",
  outbound: "/outbound-api",
  inventory: "/inventory-api",
  fulfillment: "/fulfillment-api"
} as const;

function queryValue(path: string, key: string): string | undefined {
  const query = path.includes("?") ? path.slice(path.indexOf("?") + 1) : "";
  return new URLSearchParams(query).get(key) ?? undefined;
}

function stripQuery(path: string, key: string): string {
  const cut = path.indexOf("?");
  if (cut < 0) {
    return path;
  }
  const search = new URLSearchParams(path.slice(cut + 1));
  search.delete(key);
  const next = search.toString();
  return next ? `${path.slice(0, cut)}?${next}` : path.slice(0, cut);
}

export function routeFor(path: string): string {
  const serviceHint = queryValue(path, "service");
  const cleaned = stripQuery(path, "service");
  if (cleaned.includes("/serial-recoveries") || cleaned.includes("/operations/")) {
    return PREFIX.inventory + cleaned;
  }
  if (cleaned.includes("/message-queues") && serviceHint && serviceHint in PREFIX) {
    return PREFIX[serviceHint as keyof typeof PREFIX] + cleaned;
  }
  if (cleaned.includes("/action-effects")) {
    return PREFIX.inventory + cleaned;
  }
  const taskType = queryValue(cleaned, "taskType");
  if (cleaned.includes("/inbound-orders") || cleaned.includes("/quality-inspections") || cleaned.includes("/putaways")
    || taskType === "PUTAWAY") {
    return PREFIX.inbound + cleaned;
  }
  if (cleaned.includes("/outbound-orders") || cleaned.includes("/picks") || cleaned.includes("/packings") || cleaned.includes("/shipments")
    || taskType === "PICK" || taskType === "RESTOCK") {
    return PREFIX.outbound + cleaned;
  }
  if (cleaned.includes("/transfer-receipts") || cleaned.includes("/receipt-authorizations")) {
    return PREFIX.fulfillment + cleaned;
  }
  if (cleaned.startsWith("/api/wms/v1/fulfillments") || cleaned.startsWith("/api/wms/v1/transfers")) {
    return PREFIX.fulfillment + cleaned;
  }
  return PREFIX.inventory + cleaned;
}

export function serviceFor(path: string): keyof typeof PREFIX {
  const routed = routeFor(path);
  if (routed.startsWith(PREFIX.inbound)) {
    return "inbound";
  }
  if (routed.startsWith(PREFIX.outbound)) {
    return "outbound";
  }
  if (routed.startsWith(PREFIX.fulfillment)) {
    return "fulfillment";
  }
  return "inventory";
}

export async function api(path: string, token: string | undefined, options: RequestOptions = {}): Promise<unknown> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (options.idempotencyKey) {
    headers["Idempotency-Key"] = options.idempotencyKey;
  }
  const response = await fetch(routeFor(path), {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const text = await response.text();
  const parsed = text ? safeJson(text) : {};
  if (!response.ok) {
    const error: ApiError = {
      status: response.status,
      code: typeof parsed === "object" && parsed && "code" in parsed ? String((parsed as { code: string }).code) : undefined,
      message: messageOf(parsed, response.statusText),
      retryable: typeof parsed === "object" && parsed && "retryable" in parsed
        ? Boolean((parsed as { retryable: boolean }).retryable)
        : response.status === 202,
      body: parsed
    };
    throw error;
  }
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
    (parsed as { __httpStatus?: number }).__httpStatus = response.status;
  }
  return parsed;
}

function messageOf(parsed: unknown, fallback: string): string {
  if (parsed && typeof parsed === "object") {
    const body = parsed as { message?: unknown; error?: unknown };
    if (typeof body.message === "string" && body.message) {
      return body.message;
    }
    if (typeof body.error === "string" && body.error) {
      return body.error;
    }
  }
  return fallback || "请求失败";
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return { message: text };
  }
}

export function rememberKey(operation: string): string {
  const existing = sessionStorage.getItem(operation);
  if (existing) {
    return existing;
  }
  const key = crypto.randomUUID();
  sessionStorage.setItem(operation, key);
  return key;
}

export function clearKey(operation: string): void {
  sessionStorage.removeItem(operation);
}
