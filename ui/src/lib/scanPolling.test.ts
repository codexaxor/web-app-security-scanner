import { describe, expect, it } from "vitest";
import { anyActive, POLL_MS, scanRefetchInterval } from "./scanPolling";

describe("scan polling (M8.8 item 5)", () => {
  it("detects an active scan among statuses", () => {
    expect(anyActive(["completed", "failed"])).toBe(false);
    expect(anyActive(["completed", "running"])).toBe(true);
    expect(anyActive(["queued"])).toBe(true);
    expect(anyActive([null, undefined])).toBe(false);
  });

  it("starts polling (3s) while a scan is active and the tab is visible", () => {
    expect(scanRefetchInterval(["running", "completed"], false)).toBe(POLL_MS);
  });

  it("stops polling when no scan is active", () => {
    expect(scanRefetchInterval(["completed", "failed"], false)).toBe(false);
  });

  it("pauses polling while the tab is hidden, even with an active scan", () => {
    expect(scanRefetchInterval(["running"], true)).toBe(false);
  });
});
