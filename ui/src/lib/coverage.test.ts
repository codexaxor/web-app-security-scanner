import { describe, expect, it } from "vitest";
import { coverageBanners, WARNING_BANNERS } from "./coverage";

describe("coverageBanners", () => {
  it("returns nothing when the scan was fully covered", () => {
    expect(
      coverageBanners({ tasks: [{ scanner: "sca", status: "ok" }], warningCodes: [] }),
    ).toEqual([]);
  });

  it("reports a failed scanner by its human noun, findings not updated", () => {
    expect(coverageBanners({ tasks: [{ scanner: "secret", status: "failed" }], warningCodes: [] })).toEqual([
      "The secret scanner failed in the last scan, so its findings were not updated.",
    ]);
  });

  it("reports a timed-out scanner distinctly", () => {
    expect(coverageBanners({ tasks: [{ scanner: "sca", status: "timed_out" }], warningCodes: [] })).toEqual([
      "The dependency scanner timed out in the last scan, so its findings were not updated.",
    ]);
  });

  it("pluralises the unresolved-dependency count", () => {
    expect(coverageBanners({ tasks: [], warningCodes: ["UNRESOLVED_PATH"] })).toEqual([
      "1 dependency could not be resolved.",
    ]);
    expect(
      coverageBanners({ tasks: [], warningCodes: ["UNRESOLVED_PATH", "UNRESOLVED_PATH", "UNRESOLVED_PATH"] }),
    ).toEqual(["3 dependencies could not be resolved."]);
  });

  it("maps a lockfile warning once even when repeated, and ignores unknown codes", () => {
    expect(
      coverageBanners({
        tasks: [],
        warningCodes: ["MISSING_GRADLE_LOCKFILE", "MISSING_GRADLE_LOCKFILE", "SOME_UNKNOWN_CODE"],
      }),
    ).toEqual([WARNING_BANNERS.MISSING_GRADLE_LOCKFILE]);
  });

  it("orders scanner failures, then unresolved count, then mapped warnings", () => {
    expect(
      coverageBanners({
        tasks: [{ scanner: "iac", status: "failed" }],
        warningCodes: ["MISSING_NUGET_LOCKFILE", "UNRESOLVED_PATH", "UNRESOLVED_PATH"],
      }),
    ).toEqual([
      "The infrastructure scanner failed in the last scan, so its findings were not updated.",
      "2 dependencies could not be resolved.",
      WARNING_BANNERS.MISSING_NUGET_LOCKFILE,
    ]);
  });

  it("never tells the user to change the repository (D-030)", () => {
    const all = Object.values(WARNING_BANNERS).join(" ").toLowerCase();
    for (const banned of ["add a lockfile", "commit", "fix the", "edit the repository", "push"]) {
      expect(all).not.toContain(banned);
    }
  });
});
