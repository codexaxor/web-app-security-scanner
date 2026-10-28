import { QueryClient } from "@tanstack/react-query";
import { AuthRequiredError } from "./api";

// A single QueryClient for the app. The unauthorized drop-to-sign-in is handled centrally in api.ts's request()
// (SF-3): it fires on EVERY 401, so there is no need for a QueryCache/MutationCache onError hook here (that only
// saw react-query-driven 401s and would double-fire). We keep the retry predicate: never retry a 401.
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (count, error) => !(error instanceof AuthRequiredError) && count < 1,
      refetchOnWindowFocus: false,
      staleTime: 10_000,
    },
  },
});

// --- Query keys (one source of truth) ---------------------------------------------------------------
// Keys are arrays; a prefix match (the first element) is how invalidation targets a whole family.
export const qk = {
  repositories: (params: unknown) => ["repositories", params] as const,
  repositoriesAll: ["repositories"] as const,
  repository: (id: string) => ["repository", id] as const,
  repositoryAll: ["repository"] as const,
  repositoryScans: (id: string, page: number) => ["repository-scans", id, page] as const,
  repositoryScansAll: ["repository-scans"] as const,
  scanWarnings: (scanId: string, page: number) => ["scan-warnings", scanId, page] as const,
  feed: (params: unknown) => ["feed", params] as const,
  feedAll: ["feed"] as const,
  feedSummary: (params: unknown) => ["feed-summary", params] as const,
  feedSummaryAll: ["feed-summary"] as const,
  group: (groupId: string) => ["issue-group", groupId] as const,
  groupAll: ["issue-group"] as const,
  subIssues: (groupId: string, page: number) => ["sub-issues", groupId, page] as const,
  subIssuesAll: ["sub-issues"] as const,
  subIssueEvents: (subId: string, page: number) => ["sub-issue-events", subId, page] as const,
  subIssueEventsAll: ["sub-issue-events"] as const,
  ignored: (page: number) => ["ignored", page] as const,
  ignoredAll: ["ignored"] as const,
  ignoreRules: (page: number) => ["ignore-rules", page] as const,
  ignoreRulesAll: ["ignore-rules"] as const,
  dashboard: (includeInactive: boolean) => ["dashboard", includeInactive] as const,
  dashboardAll: ["dashboard"] as const,
  scansRecent: ["scans-recent"] as const,
  scanDetail: (scanId: string) => ["scan-detail", scanId] as const,
  issueCategories: (params: unknown) => ["issue-categories", params] as const,
  workspaceSettings: ["workspace-settings"] as const,
};

// --- Invalidation map -------------------------------------------------------------------------------
// After a mutation, invalidate EVERY query family a user could see change — including the side panel's own
// group header (`issue-group`) and per-sub-issue history (`sub-issue-events`), which SF-1 adds to all four
// kinds (their omission was the "Medium 65" stale-header bug). Documented exactly so a new screen cannot
// forget to refresh (R3/R5); the table-driven test in queryClient.test.ts asserts this map verbatim.
export type MutationKind = "triage" | "settings" | "scan" | "workspaceSettings";

export const AFFECTED: Record<MutationKind, readonly (readonly unknown[])[]> = {
  // ignore / unignore / delete-rule: statuses and every count move, plus the open panel's header + history.
  triage: [qk.subIssuesAll, qk.subIssueEventsAll, qk.groupAll, qk.feedAll, qk.feedSummaryAll, qk.ignoredAll,
    qk.ignoreRulesAll, qk.dashboardAll, qk.repositoriesAll, qk.repositoryAll],
  // repo settings: sensitivity re-scores (scores/bands move), include-dev-deps/active change rollups + feed; a
  // scan-branch change (D-076) also moves the scans/recent list (branch column) and the repo row's branch label.
  settings: [qk.repositoriesAll, qk.repositoryAll, qk.repositoryScansAll, qk.feedAll, qk.feedSummaryAll,
    qk.dashboardAll, qk.subIssuesAll, qk.subIssueEventsAll, qk.groupAll, qk.scansRecent],
  // start scan / status poll reaching terminal: counts, statuses and the recent-scans list all move.
  scan: [qk.repositoriesAll, qk.repositoryAll, qk.repositoryScansAll, qk.feedAll, qk.feedSummaryAll,
    qk.dashboardAll, qk.ignoredAll, qk.subIssuesAll, qk.subIssueEventsAll, qk.groupAll, qk.scansRecent],
  // EPSS toggle: LOW_EPSS auto-ignore + scoring tiers move feed/ignored/dashboard/repo counts, panel + history.
  workspaceSettings: [qk.workspaceSettings, qk.feedAll, qk.feedSummaryAll, qk.ignoredAll, qk.dashboardAll,
    qk.subIssuesAll, qk.subIssueEventsAll, qk.groupAll, qk.repositoriesAll, qk.repositoryAll],
};

export function invalidateFor(kind: MutationKind): Promise<void> {
  return Promise.all(
    AFFECTED[kind].map((key) => queryClient.invalidateQueries({ queryKey: key })),
  ).then(() => undefined);
}
