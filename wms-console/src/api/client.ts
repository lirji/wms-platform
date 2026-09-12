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

export function routeFor(path: string): string {
  if (path.includes("/inbound-orders") || path.includes("/quality-inspections") || path.includes("/putaways")) {
    return PREFIX.inbound + path;
  }
  if (path.includes("/outbound-orders") || path.includes("/picks") || path.includes("/packings") || path.includes("/shipments")) {
    return PREFIX.outbound + path;
  }
  if (path.startsWith("/api/wms/v1/fulfillments") || path.startsWith("/api/wms/v1/transfers")) {
    return PREFIX.fulfillment + path;
  }
  return PREFIX.inventory + path;
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
      message: typeof parsed === "object" && parsed && "message" in parsed
        ? String((parsed as { message: string }).message)
        : response.statusText,
      retryable: typeof parsed === "object" && parsed && "retryable" in parsed
        ? Boolean((parsed as { retryable: boolean }).retryable)
        : response.status === 202,
      body: parsed
    };
    throw error;
  }
  return parsed;
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
