import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { advisoryLink, exactTime, relativeTime } from "./format";

// Fixed clock so the buckets are deterministic. Wording is the environment default locale (en-US here); the
// assertions target the BUCKET (unit + rounded number), which is what the S10a bug was about.
const NOW = new Date("2026-06-15T12:00:00Z").getTime();
const ago = (ms: number) => new Date(NOW - ms).toISOString();
const ahead = (ms: number) => new Date(NOW + ms).toISOString();
const S = 1000;
const MIN = 60 * S;
const HR = 60 * MIN;
const DAY = 24 * HR;

describe("relativeTime", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => vi.useRealTimers());

  it("returns — for null/undefined/invalid", () => {
    expect(relativeTime(null)).toBe("—");
    expect(relativeTime(undefined)).toBe("—");
    expect(relativeTime("not-a-date")).toBe("—");
    expect(relativeTime("")).toBe("—");
  });

  it("collapses anything under a minute to 'just now'", () => {
    expect(relativeTime(ago(0))).toBe("just now");
    expect(relativeTime(ago(59 * S))).toBe("just now");
  });

  it("switches to minutes at exactly 60s (no unit shift — the S10a bug)", () => {
    expect(relativeTime(ago(60 * S))).toBe("1 minute ago");
    expect(relativeTime(ago(59 * MIN))).toBe("59 minutes ago");
  });

  it("switches to hours at exactly 60m", () => {
    expect(relativeTime(ago(60 * MIN))).toBe("1 hour ago");
    expect(relativeTime(ago(23 * HR))).toBe("23 hours ago");
    // the reported bug: 8.7h must read in hours, not minutes
    expect(relativeTime(ago(8 * HR + 40 * MIN))).toBe("9 hours ago");
  });

  it("switches to days at exactly 24h", () => {
    expect(relativeTime(ago(24 * HR))).toBe("1 day ago");
    expect(relativeTime(ago(29 * DAY))).toBe("29 days ago");
    // the reported bug: 3.4d must read in days, not hours
    expect(relativeTime(ago(3 * DAY + 10 * HR))).toBe("3 days ago");
  });

  it("collapses 30 days or older to an absolute date (no relative wording)", () => {
    const out = relativeTime(ago(30 * DAY));
    expect(out).not.toMatch(/ago/);
    expect(out).toMatch(/2026|May/); // ~2026-05-16, locale-formatted
  });

  it("formats future timestamps with 'in …'", () => {
    expect(relativeTime(ahead(9 * HR))).toBe("in 9 hours");
    expect(relativeTime(ahead(2 * MIN))).toBe("in 2 minutes");
    expect(relativeTime(ahead(30 * S))).toBe("just now");
  });
});

describe("advisoryLink", () => {
  it("builds an NVD link only for a well-formed CVE id", () => {
    expect(advisoryLink("CVE-2024-1234")).toBe("https://nvd.nist.gov/vuln/detail/CVE-2024-1234");
    expect(advisoryLink("cve-2024-1234")).toBe("https://nvd.nist.gov/vuln/detail/CVE-2024-1234");
  });

  it("builds a GitHub advisory link only for a well-formed GHSA id", () => {
    expect(advisoryLink("GHSA-wh77-3x4m-4q9g")).toBe("https://github.com/advisories/GHSA-WH77-3X4M-4Q9G");
  });

  it("returns null for anything that is not a recognised, exact id (no link rendered)", () => {
    for (const bad of [
      null,
      undefined,
      "",
      "   ",
      "javascript:alert(1)",
      "CVE-2024-1", // too few digits
      "CVE-2024-1/../x", // path-traversal-looking junk
      "CVE-2024-1234 ", // trailing whitespace → not an exact match
      " CVE-2024-1234", // leading whitespace
      "https://evil.example/CVE-2024-1234",
      "GHSA-xxxx", // malformed GHSA
      "not-an-id",
    ]) {
      expect(advisoryLink(bad as string | null | undefined)).toBeNull();
    }
  });
});

describe("exactTime", () => {
  it("returns null for null/invalid, a string otherwise", () => {
    expect(exactTime(null)).toBeNull();
    expect(exactTime("nope")).toBeNull();
    const out = exactTime("2026-06-15T12:00:00Z");
    expect(typeof out).toBe("string");
    expect(out).toMatch(/2026/);
  });
});
