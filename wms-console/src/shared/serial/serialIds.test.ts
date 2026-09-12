import { describe, expect, it } from "vitest";
import { countObservation, countText, parseSerialIds, qualityObservation, receiptObservation } from "./serialIds";

describe("parseSerialIds", () => {
  it("trims, uppercases and rejects duplicates", () => {
    expect(parseSerialIds(" sn-1 \nsn-2").serialIds).toEqual(["SN-1", "SN-2"]);
    expect(parseSerialIds("SN-1\nsn-1").error).toMatch(/重复/);
    expect(parseSerialIds(Array.from({ length: 201 }, (_, index) => `SN-${index}`).join("\n")).error).toMatch(/最多 200/);
  });
});

describe("receiptObservation", () => {
  it("omits empty input and keeps schemaVersion 1", () => {
    expect(receiptObservation("")).toBeUndefined();
    expect(receiptObservation("sn-a\nsn-b")).toEqual({
      schemaVersion: 1,
      serialIds: ["SN-A", "SN-B"]
    });
    expect(countText(["SN-A", "SN-B"])).toBe("2");
  });
});

describe("qualityObservation", () => {
  it("rejects overlapping accepted and rejected identities", () => {
    expect(() => qualityObservation("SN-1", "sn-1")).toThrow(/互斥/);
    expect(qualityObservation("SN-1", "SN-2")).toEqual({
      schemaVersion: 1,
      acceptedSerials: ["SN-1"],
      rejectedSerials: ["SN-2"]
    });
  });
});

describe("countObservation", () => {
  it("omits quantity-only rows and sends empty set for all missing", () => {
    expect(countObservation("", false)).toBeUndefined();
    expect(countObservation("", true)).toEqual({ schemaVersion: 1, serialIds: [] });
    expect(() => countObservation("SN-1", true)).toThrow(/全部未见/);
  });
});
