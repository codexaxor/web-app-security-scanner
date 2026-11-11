import { describe, expect, it } from "vitest";
import { coverageBanners, WARNING_BANNERS } from "./coverage";

/** Build the warnings array from bare codes (message defaults to ""), as most cases don't need a message. */
const w = (...codes: string[]) => codes.map((code) => ({ code, message: "" }));

describe("coverageBanners", () => {
  it("returns nothing when the scan was fully covered", () => {
    expect(coverageBanners({ tasks: [{ scanner: "sca", status: "ok" }], warnings: [] })).toEqual([]);
  });

  it("reports a failed scanner by its human noun, findings not updated", () => {
    expect(coverageBanners({ tasks: [{ scanner: "secret", status: "failed" }], warnings: [] })).toEqual([
      "The secret scanner failed in the last scan, so its findings were not updated.",
    ]);
  });

  it("reports a timed-out scanner distinctly", () => {
    expect(coverageBanners({ tasks: [{ scanner: "sca", status: "timed_out" }], warnings: [] })).toEqual([
      "The dependency scanner timed out in the last scan, so its findings were not updated.",
    ]);
  });

  it("pluralises the unresolved-dependency count", () => {
    expect(coverageBanners({ tasks: [], warnings: w("UNRESOLVED_PATH") })).toEqual([
      "1 dependency could not be resolved.",
    ]);
    expect(
      coverageBanners({ tasks: [], warnings: w("UNRESOLVED_PATH", "UNRESOLVED_PATH", "UNRESOLVED_PATH") }),
    ).toEqual(["3 dependencies could not be resolved."]);
  });

  it("maps a lockfile warning once even when repeated, and ignores unknown codes", () => {
    expect(
      coverageBanners({
        tasks: [],
        warnings: w("MISSING_GRADLE_LOCKFILE", "MISSING_GRADLE_LOCKFILE", "SOME_UNKNOWN_CODE"),
      }),
    ).toEqual([WARNING_BANNERS.MISSING_GRADLE_LOCKFILE]);
  });

  it("orders scanner failures, then unresolved count, then mapped warnings", () => {
    expect(
      coverageBanners({
        tasks: [{ scanner: "iac", status: "failed" }],
        warnings: w("MISSING_NUGET_LOCKFILE", "UNRESOLVED_PATH", "UNRESOLVED_PATH"),
      }),
    ).toEqual([
      "The infrastructure scanner failed in the last scan, so its findings were not updated.",
      "2 dependencies could not be resolved.",
      WARNING_BANNERS.MISSING_NUGET_LOCKFILE,
    ]);
  });

  it("shows the SAST_LANGUAGE_UNCOVERED message verbatim (it names the languages; M8.15 S5)", () => {
    const message =
      "SAST has no rule pack for Rust, Vue, so code in those languages was not analyzed for code issues.";
    expect(
      coverageBanners({ tasks: [], warnings: [{ code: "SAST_LANGUAGE_UNCOVERED", message }] }),
    ).toEqual([message]);
  });

  it("surfaces an uncovered-language banner once, after mapped warnings keep their fixed text", () => {
    const message = "SAST has no rule pack for Go, so code in that language was not analyzed for code issues.";
    expect(
      coverageBanners({
        tasks: [],
        warnings: [
          { code: "MISSING_GRADLE_LOCKFILE", message: "" },
          { code: "SAST_LANGUAGE_UNCOVERED", message },
          { code: "SAST_LANGUAGE_UNCOVERED", message },
        ],
      }),
    ).toEqual([WARNING_BANNERS.MISSING_GRADLE_LOCKFILE, message]);
  });

  it("maps SAST_FILES_SKIPPED to its fixed banner text once", () => {
    expect(
      coverageBanners({
        tasks: [],
        warnings: w("SAST_FILES_SKIPPED", "SAST_FILES_SKIPPED"),
      }),
    ).toEqual([WARNING_BANNERS.SAST_FILES_SKIPPED]);
  });

  it("never tells the user to change the repository (D-030)", () => {
    const all = Object.values(WARNING_BANNERS).join(" ").toLowerCase();
    for (const banned of ["add a lockfile", "commit", "fix the", "edit the repository", "push"]) {
      expect(all).not.toContain(banned);
    }
  });
});
