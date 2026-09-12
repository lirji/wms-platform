import { describe, expect, it } from "vitest";
import { sanitizeReturnTo } from "./returnTo";

describe("sanitizeReturnTo", () => {
  it("keeps in-app paths", () => {
    expect(sanitizeReturnTo("/")).toBe("/");
    expect(sanitizeReturnTo("/inventory")).toBe("/inventory");
  });

  it("rejects open redirects", () => {
    expect(sanitizeReturnTo("//evil.example")).toBe("/");
    expect(sanitizeReturnTo("https://evil.example")).toBe("/");
    expect(sanitizeReturnTo(null)).toBe("/");
  });
});
