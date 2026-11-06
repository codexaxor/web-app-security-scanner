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

  it("rejects the encoded, case and whitespace variants (M8.13 review)", () => {
    for (const evil of [
      "%2F%2Fevil.com", // still-encoded "//" — no leading slash
      "%252F%252Fevil.com", // double-encoded "//"
      "/\tevil", // tab
      "\t//evil.com", // leading tab before "//"
      "/\u3000/evil", // ideographic space
      "\u3000//evil.com",
      "JAVASCRIPT:alert(1)",
      "JaVaScRiPt:alert(1)",
    ]) {
      expect(safeNextPath(evil), `expected fallback for ${JSON.stringify(evil)}`).toBe(DEFAULT_NEXT);
    }
  });

  it("a double-encoded next decoded once by the query string stays rejected", () => {
    // ?next=%252F%252Fevil.com → URLSearchParams decodes once → "%2F%2Fevil.com" (no leading "/") → fallback.
    expect(safeNextPath(new URLSearchParams("next=%252F%252Fevil.com").get("next"))).toBe(DEFAULT_NEXT);
    // ?next=%2F%252F%252Fevil.com → "/%2F%2Fevil.com": a literal same-origin path (the %2F are not separators);
    // if kept it must never resolve off-origin.
    const kept = safeNextPath(new URLSearchParams("next=%2F%252F%252Fevil.com").get("next"));
    expect(new URL(kept, "http://secscan.invalid").origin).toBe("http://secscan.invalid");
    expect(kept.startsWith("//")).toBe(false);
  });

  it("honors a custom fallback", () => {
    expect(safeNextPath("//evil.com", "/")).toBe("/");
  });
});
