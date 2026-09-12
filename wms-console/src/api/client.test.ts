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
    expect(serviceFor("/api/wms/v1/jobs?warehouseId=WH-A")).toBe("inventory");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/count-plans/P1/freeze-requests")).toBe("inventory");
  });

  it("sends outbound picks and cancellations to outbound", () => {
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/tasks/T1/picks")).toBe("outbound");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/outbound-orders/O1/cancellations")).toBe("outbound");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/outbound-orders/O1/pick-tasks")).toBe("outbound");
  });

  it("routes warehouse tasks by required taskType", () => {
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/tasks?taskType=PUTAWAY")).toBe("inbound");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/tasks/T1?taskType=PUTAWAY")).toBe("inbound");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/tasks/T1/claims?taskType=PICK")).toBe("outbound");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/tasks?taskType=RESTOCK")).toBe("outbound");
    expect(serviceFor("/api/wms/v1/warehouses/WH-A/tasks/T1/action-effects?taskType=PICK")).toBe("inventory");
  });

  it("sends transfer receipts to fulfillment", () => {
    expect(serviceFor("/api/wms/v1/warehouses/WH-B/transfer-receipts")).toBe("fulfillment");
    expect(serviceFor("/api/wms/v1/transfers/TR-1/receipt-authorizations")).toBe("fulfillment");
    expect(serviceFor("/api/wms/v1/transfers/TR-1/issues")).toBe("fulfillment");
    expect(serviceFor("/api/wms/v1/fulfillments/F1/attempts")).toBe("fulfillment");
  });
});
