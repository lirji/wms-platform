import { describe, expect, it } from "vitest";
import { hasScope } from "./can";

describe("hasScope", () => {
  it("hides a command when the token lacks that job scope", () => {
    expect(hasScope(["masterdata.read"], "inbound.create")).toBe(false);
    expect(hasScope(["inbound.create", "inbound.read"], "inbound.create")).toBe(true);
    expect(hasScope(["outbound.pick"], ["outbound.pick", "stock.move"])).toBe(true);
    expect(hasScope([], "inbound.create")).toBe(false);
  });
});
