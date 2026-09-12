import { describe, expect, it } from "vitest";
import { asOfMeta, field, pageItems, qtyField } from "./envelope";

describe("envelope", () => {
  it("reads CursorPage items and quantity strings", () => {
    expect(pageItems({ items: [{ onHandQty: "12.000" }] })).toHaveLength(1);
    expect(qtyField({ onHandQty: "12.000" }, "onHandQty")).toBe("12.000");
    expect(field({ id: "WH-A" }, "id", "warehouseId")).toBe("WH-A");
  });

  it("marks stale queries after 30s lag", () => {
    expect(asOfMeta({ asOf: "2026-09-12T00:00:00Z", lagSeconds: 45 }).stale).toBe(true);
  });
});
