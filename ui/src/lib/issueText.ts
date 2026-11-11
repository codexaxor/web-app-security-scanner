import type { IssueGroupView, SubIssueView } from "../api";
import { humanizeRuleId } from "./labels";

// Deterministic "TL;DR" and "How do I fix it?" text for the issue side panel (M8.6 item 5). Built ONLY from
// stored fields (type, counts, CWE phrase, package, installed/fixed versions, file paths, secret rule id,
// title/subtitle) — no AI, no free scanner text beyond what the server already stored. Pure functions so they
// are unit-tested across SCA (single/multi/no-fix), SAST, secret, IaC and EOL.

function typeOf(group: IssueGroupView | undefined, subs: SubIssueView[]): string {
  return group?.type ?? subs[0]?.type ?? "";
}

function distinctFiles(subs: SubIssueView[]): number {
  return new Set(subs.map((s) => s.filePath).filter((p): p is string => !!p)).size;
}

function distinctVulns(subs: SubIssueView[]): number {
  const ids = new Set(subs.map((s) => s.vulnId).filter((v): v is string => !!v));
  return ids.size || subs.length;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** The one-line "TL;DR" summary for the group, from stored fields only. */
export function issueSummary(group: IssueGroupView | undefined, subs: SubIssueView[]): string {
  const type = typeOf(group, subs);

  if (type === "sca") {
    const pkg = group?.title ?? subs[0]?.packageName ?? "This dependency";
    const installed = subs[0]?.installedVersion;
    const vulns = distinctVulns(subs);
    const files = distinctFiles(subs);
    const kev = subs.some((s) => s.inKev);
    let text = `${pkg}${installed ? ` ${installed}` : ""} has ${plural(vulns, "known vulnerability", "known vulnerabilities")}`;
    text += files > 1 ? ` across ${plural(files, "manifest", "manifests")}.` : ".";
    if (kev) {
      text += " One is on the CISA KEV list of actively exploited vulnerabilities.";
    }
    return text;
  }

  if (type === "sast") {
    const weakness = subs[0]?.cwePhrase ?? group?.level2Category ?? humanizeRuleId(group?.title);
    const places = distinctFiles(subs) || subs.length;
    return `${weakness} was found in ${plural(places, "location", "locations")}.`;
  }

  if (type === "secret") {
    const kind = subs[0]?.ruleId ?? "secret";
    const file = subs[0]?.filePath;
    return `A secret (${kind}) was detected${file ? ` in ${file}` : ""}.`;
  }

  if (type === "iac") {
    const title = group?.title ?? "An infrastructure rule";
    const files = distinctFiles(subs) || subs.length;
    return `${title} — a misconfiguration flagged in ${plural(files, "file", "files")}.`;
  }

  if (type === "eol") {
    const what = `${group?.title ?? "A runtime"}${group?.subtitle ? ` ${group.subtitle}` : ""}`;
    return `${what} is past its end-of-life date and no longer receives security updates.`;
  }

  return group?.title ?? "Security finding.";
}

/** The "How do I fix it?" guidance for the group, or null when there is nothing actionable to say. */
export function issueFix(group: IssueGroupView | undefined, subs: SubIssueView[]): string | null {
  const type = typeOf(group, subs);

  if (type === "sca") {
    const pkg = group?.title ?? subs[0]?.packageName ?? "the dependency";
    const fixed = group?.fixedVersions ?? [];
    if (fixed.length === 1) {
      return `Upgrade ${pkg} to ${fixed[0]}.`;
    }
    if (fixed.length > 1) {
      return `Upgrade ${pkg}; the fixed version differs per advisory — see each location below.`;
    }
    return `No fixed version is available yet. Consider replacing or removing ${pkg}, or applying a mitigation.`;
  }

  if (type === "sast") {
    const weakness = subs[0]?.cwePhrase ?? group?.level2Category;
    return weakness
      ? `Review the flagged code at each location and remediate the ${weakness.toLowerCase()}.`
      : "Review and remediate the flagged code at each location.";
  }

  if (type === "secret") {
    return (
      "Revoke or rotate the exposed secret first, then remove it from the code and move it to a secret " +
      "store. Removing it from the current code does not remove it from the repository's git history."
    );
  }

  if (type === "iac") {
    const rule = group?.subtitle ? ` (${group.subtitle})` : "";
    return `Correct the flagged configuration${rule} at each location.`;
  }

  if (type === "eol") {
    return `Upgrade to a supported release of ${group?.title ?? "the runtime"}.`;
  }

  return null;
}
