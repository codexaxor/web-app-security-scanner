import { describe, expect, it } from "vitest";
import { firstRunComplete, firstRunSteps, nextStepKey } from "./firstRunChecklist";

describe("firstRunChecklist", () => {
  it("marks all steps not-done and incomplete for a brand-new workspace", () => {
    const c = { sources: 0, repositories: 0, scans: 0 };
    expect(firstRunSteps(c).map((s) => s.done)).toEqual([false, false, false]);
    expect(firstRunComplete(c)).toBe(false);
    expect(nextStepKey(c)).toBe("source");
  });

  it("advances the next step as each count becomes non-zero", () => {
    expect(nextStepKey({ sources: 1, repositories: 0, scans: 0 })).toBe("repository");
    expect(nextStepKey({ sources: 1, repositories: 2, scans: 0 })).toBe("scan");
  });

  it("is complete only when a source, a repository and a scan all exist", () => {
    expect(firstRunComplete({ sources: 1, repositories: 1, scans: 1 })).toBe(true);
    expect(nextStepKey({ sources: 1, repositories: 1, scans: 1 })).toBeNull();
    expect(firstRunComplete({ sources: 5, repositories: 5, scans: 0 })).toBe(false);
  });
});
