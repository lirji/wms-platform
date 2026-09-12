import { describe, expect, it } from "vitest";
import { asOfMeta, field, nextCursorOf, pageItems, qtyField, withQuery } from "./envelope";

describe("envelope", () => {
  it("reads CursorPage items and quantity strings", () => {
    expect(pageItems({ items: [{ onHandQty: "12.000" }] })).toHaveLength(1);
    expect(qtyField({ onHandQty: "12.000" }, "onHandQty")).toBe("12.000");
    expect(field({ id: "WH-A" }, "id", "warehouseId")).toBe("WH-A");
  });

  it("marks stale queries after 30s lag", () => {
    expect(asOfMeta({ asOf: "2026-09-12T00:00:00Z", lagSeconds: 45 }).stale).toBe(true);
  });

  it("keeps cursor query on the contract path", () => {
    expect(nextCursorOf({ items: [], nextCursor: "c2" })).toBe("c2");
    expect(withQuery("/api/wms/v1/warehouses/WH-A/inbound-orders", { cursor: "c2", limit: "50" }))
      .toBe("/api/wms/v1/warehouses/WH-A/inbound-orders?cursor=c2&limit=50");
  });
});
