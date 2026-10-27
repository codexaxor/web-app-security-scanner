// Coverage banner for the repository page (M8.6 item 4, approval item 10). Built ONLY from stored scan data
// (the latest scan's per-scanner task statuses and its scan-warning codes). It states what the last scan did
// NOT fully cover; it never suggests changing the repository (D-030). No signal → no banner.

/** A scanner id → the human noun used in a "the <noun> scanner failed" line. */
const SCANNER_NOUN: Record<string, string> = {
  sca: "dependency",
  secret: "secret",
  sast: "code",
  iac: "infrastructure",
  eol: "runtime",
};

/**
 * The warning-code → banner-text map (approval item 10). Only codes that signal a COVERAGE gap are listed; any
 * other code is ignored (raw codes are never surfaced). UNRESOLVED_PATH is handled separately because it is
 * counted. Each message is descriptive and never tells the user to change the repository (D-030).
 */
export const WARNING_BANNERS: Record<string, string> = {
  MISSING_GRADLE_LOCKFILE:
    "Dependencies were resolved without a Gradle lockfile, so results may be less precise.",
  MISSING_NUGET_LOCKFILE:
    "Dependencies were resolved without a NuGet lockfile, so results may be less precise.",
  GRADLE_BOM_FETCH_FAILED: "A Gradle BOM could not be fetched, so some dependency versions are unresolved.",
  GRADLE_UNPARSEABLE_CONSTRUCT:
    "Part of a Gradle build file could not be parsed, so some dependencies may be missing.",
  SCOPE_MANIFEST_UNREADABLE: "A dependency manifest could not be read, so some dependencies may be missing.",
  LANGUAGE_DETECT_FAILED: "Language detection did not complete for the last scan.",
  EOL_COVERAGE_GAP: "End-of-life data was incomplete, so some runtimes may not have been checked.",
  SECSCAN_CONFIG_PARSE_ERROR:
    "The repository's .secscan.yml could not be parsed, so its ignore rules were not applied.",
  SECSCAN_CONFIG_NO_REASON: "An ignore entry in .secscan.yml was skipped because it had no reason.",
  SECSCAN_CONFIG_OUTSIDE_CLONE: "An ignore path in .secscan.yml pointed outside the repository and was skipped.",
  SCANNER_IMAGE_MISSING: "A scanner was unavailable for the last scan, so some findings were not updated.",
};

export interface CoverageInput {
  /** The latest scan's per-scanner outcomes. */
  tasks: { scanner: string; status: string }[];
  /** The latest scan's warning codes (one entry per warning row; duplicates are meaningful for counts). */
  warningCodes: string[];
}

/**
 * The coverage banner lines, in a stable order: scanner failures/timeouts first, then the unresolved-dependency
 * count, then the distinct mapped warning messages. Empty when there is nothing to say.
 */
export function coverageBanners(input: CoverageInput): string[] {
  const out: string[] = [];

  for (const t of input.tasks) {
    if (t.status === "failed" || t.status === "timed_out") {
      const noun = SCANNER_NOUN[t.scanner] ?? t.scanner;
      const verb = t.status === "timed_out" ? "timed out" : "failed";
      out.push(`The ${noun} scanner ${verb} in the last scan, so its findings were not updated.`);
    }
  }

  const unresolved = input.warningCodes.filter((c) => c === "UNRESOLVED_PATH").length;
  if (unresolved > 0) {
    out.push(`${unresolved} ${unresolved === 1 ? "dependency" : "dependencies"} could not be resolved.`);
  }

  const seen = new Set<string>();
  for (const code of input.warningCodes) {
    const msg = WARNING_BANNERS[code];
    if (msg && !seen.has(code)) {
      seen.add(code);
      out.push(msg);
    }
  }

  return out;
}
