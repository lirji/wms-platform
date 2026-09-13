import { describe, expect, it } from "vitest";
import { appendIdentityLine } from "./scanIdentity";

describe("appendIdentityLine", () => {
  it("requires a current ownerEpoch and uppercases the serial", () => {
    expect(appendIdentityLine("", "sn-1", "2")).toBe("SN-1 2");
    expect(appendIdentityLine("SN-1 2", "sn-2", "2")).toBe("SN-1 2\nSN-2 2");
    expect(() => appendIdentityLine("", "SN-1", "")).toThrow(/ownerEpoch/);
    expect(() => appendIdentityLine("", "SN-1", "1.5")).toThrow(/ownerEpoch/);
  });
});
