export type TokenClaims = {
  subject: string;
  name: string;
  enterpriseId: string;
  warehouses: string[];
  scopes: string[];
};

export function tokenClaims(token?: string): TokenClaims {
  if (!token) {
    return { subject: "", name: "", enterpriseId: "", warehouses: [], scopes: [] };
  }
  try {
    const payload = token.split(".")[1];
    if (!payload) {
      return { subject: "", name: "", enterpriseId: "", warehouses: [], scopes: [] };
    }
    const raw = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))) as Record<string, unknown>;
    return {
      subject: text(raw.sub),
      name: text(raw.name, raw.preferred_username, raw.sub),
      enterpriseId: text(raw.enterprise_id, raw.owner),
      warehouses: list(raw.warehouses),
      scopes: unique([...list(raw.scope), ...list(raw.permissions)])
    };
  } catch {
    return { subject: "", name: "", enterpriseId: "", warehouses: [], scopes: [] };
  }
}

function text(...values: unknown[]): string {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }
  return "";
}

function list(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.flatMap((item) => list(item));
  }
  if (typeof value === "string" && value.trim()) {
    return value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean);
  }
  return [];
}

function unique(values: string[]): string[] {
  return [...new Set(values)];
}
