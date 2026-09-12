import { describe, expect, it } from "vitest";
import { isSyncPending, isTerminalSuccess } from "./accepted";

describe("accepted", () => {
  it("keeps 202 and PENDING as not terminal", () => {
    expect(isSyncPending({ __httpStatus: 202, operationId: "OP-1" })).toBe(true);
    expect(isTerminalSuccess({ __httpStatus: 202, stockSyncStatus: "PENDING" })).toBe(false);
    expect(isTerminalSuccess({ __httpStatus: 200, stockSyncStatus: "APPLIED" })).toBe(true);
  });
});
