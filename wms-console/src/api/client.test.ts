import { describe, expect, it } from "vitest";
import { routeFor, serviceFor } from "./client";

describe("API routing", () => {
  it("sends inbound receipts to inbound", () => {
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/inbound-orders")).toBe("inbound");
    expect(routeFor("/api/wms/v1/warehouses/WH-A/inbound-orders")).toContain("/inbound-api/");
  });

  it("keeps inventory queries on inventory", () => {
    expect(serviceFor("/api/wms/v1/inventory")).toBe("inventory");
    expect(serviceFor("/api/wms/v1/skus")).toBe("inventory");
  });
});
