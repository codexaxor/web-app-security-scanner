import { describe, expect, it } from "vitest";
import { DEFAULT_NEXT, safeNextPath } from "./safeNext";

describe("safeNextPath", () => {
  it("keeps a same-origin absolute path (with query and hash)", () => {
    expect(safeNextPath("/dashboard")).toBe("/dashboard");
    expect(safeNextPath("/feed/40000000-0000-0000-0000-000000000001")).toBe(
      "/feed/40000000-0000-0000-0000-000000000001",
    );
    expect(safeNextPath("/feed?view=all")).toBe("/feed?view=all");
    expect(safeNextPath("/repositories/1?tab=issues&issue=x")).toBe("/repositories/1?tab=issues&issue=x");
    expect(safeNextPath("/")).toBe("/");
  });

  it("falls back for empty / missing input", () => {
    expect(safeNextPath(null)).toBe(DEFAULT_NEXT);
    expect(safeNextPath(undefined)).toBe(DEFAULT_NEXT);
    expect(safeNextPath("")).toBe(DEFAULT_NEXT);
  });

  it("rejects open-redirect attempts", () => {
    for (const evil of [
      "//evil.com", // protocol-relative
      "///evil.com",
      "/\\evil.com", // backslash that normalizes to //
      "\\/evil.com",
      "https://evil.com",
      "http://evil.com",
      "javascript:alert(1)",
      "data:text/html,evil",
      "evil.com", // no leading slash
      "/ /evil", // whitespace smuggling
      "/evil\nX", // embedded control char
    ]) {
      expect(safeNextPath(evil), `expected fallback for ${JSON.stringify(evil)}`).toBe(DEFAULT_NEXT);
    }
  });

  it("honors a custom fallback", () => {
    expect(safeNextPath("//evil.com", "/")).toBe("/");
  });
});
