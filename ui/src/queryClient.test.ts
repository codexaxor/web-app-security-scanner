import { describe, expect, it } from "vitest";
import { AFFECTED, qk, type MutationKind } from "./queryClient";

// SF-6: pin the invalidation map so a future edit cannot silently drop (or add) a family. The EXPECTED map below
// is the independent "documentation" — it must list EXACTLY the families each mutation kind invalidates. Every
// kind includes the side panel's own group header (`issue-group`) and per-sub-issue history (`sub-issue-events`),
// which SF-1 added (their omission was the "Medium 65" stale-header bug).
const EXPECTED: Record<MutationKind, readonly (readonly unknown[])[]> = {
  triage: [qk.subIssuesAll, qk.subIssueEventsAll, qk.groupAll, qk.feedAll, qk.feedSummaryAll, qk.ignoredAll,
    qk.ignoreRulesAll, qk.dashboardAll, qk.repositoriesAll],
  settings: [qk.repositoriesAll, qk.feedAll, qk.feedSummaryAll, qk.dashboardAll, qk.subIssuesAll,
    qk.subIssueEventsAll, qk.groupAll],
  scan: [qk.repositoriesAll, qk.feedAll, qk.feedSummaryAll, qk.dashboardAll, qk.ignoredAll, qk.subIssuesAll,
    qk.subIssueEventsAll, qk.groupAll, qk.scansRecent],
  workspaceSettings: [qk.workspaceSettings, qk.feedAll, qk.feedSummaryAll, qk.ignoredAll, qk.dashboardAll,
    qk.subIssuesAll, qk.subIssueEventsAll, qk.groupAll, qk.repositoriesAll],
};

const asSet = (keys: readonly (readonly unknown[])[]) => new Set(keys.map((k) => JSON.stringify(k)));

describe("invalidation map", () => {
  const kinds = Object.keys(EXPECTED) as MutationKind[];

  it.each(kinds)("%s invalidates exactly its documented families", (kind) => {
    expect(asSet(AFFECTED[kind])).toEqual(asSet(EXPECTED[kind]));
    // no duplicate families within a kind
    expect(AFFECTED[kind].length).toBe(asSet(AFFECTED[kind]).size);
  });

  it("every mutation kind refreshes the side panel header and history (SF-1)", () => {
    for (const kind of kinds) {
      const families = asSet(AFFECTED[kind]);
      expect(families.has(JSON.stringify(qk.groupAll))).toBe(true);
      expect(families.has(JSON.stringify(qk.subIssueEventsAll))).toBe(true);
    }
  });

  it("a scan refreshes the recent-scans list, ignored and statuses (SF-6)", () => {
    const families = asSet(AFFECTED.scan);
    expect(families.has(JSON.stringify(qk.scansRecent))).toBe(true);
    expect(families.has(JSON.stringify(qk.ignoredAll))).toBe(true);
    expect(families.has(JSON.stringify(qk.subIssuesAll))).toBe(true);
  });

  it("the EPSS toggle also refreshes the repositories rollups (SF-6)", () => {
    expect(asSet(AFFECTED.workspaceSettings).has(JSON.stringify(qk.repositoriesAll))).toBe(true);
  });
});
