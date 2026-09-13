import { describe, expect, it } from "vitest";
import { statusTone } from "./StatusChip";

describe("statusTone", () => {
  it("maps published statuses onto the five WMS tag tones", () => {
    expect(statusTone("OPEN")).toBe("processing");
    expect(statusTone("PLANNED")).toBe("waiting");
    expect(statusTone("APPLIED")).toBe("success");
    expect(statusTone("HOLD")).toBe("error");
    expect(statusTone("CANCELLED")).toBe("closed");
  });
});
