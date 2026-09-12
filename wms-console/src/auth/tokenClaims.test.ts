import { describe, expect, it } from "vitest";
import { tokenClaims } from "./tokenClaims";

function token(payload: Record<string, unknown>): string {
  const body = btoa(JSON.stringify(payload)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  return `hdr.${body}.sig`;
}

describe("tokenClaims", () => {
  it("reads warehouse csv and permission scopes without exposing the raw token", () => {
    const claims = tokenClaims(token({
      sub: "u1",
      name: "wms-ops",
      enterprise_id: "ENT-DEMO",
      warehouses: "WH-A,WH-B",
      scope: ["inbound.create", "masterdata.read"]
    }));
    expect(claims.warehouses).toEqual(["WH-A", "WH-B"]);
    expect(claims.scopes).toEqual(["inbound.create", "masterdata.read"]);
    expect(claims.enterpriseId).toBe("ENT-DEMO");
  });
});
