import { QueryClient, QueryCache, MutationCache } from "@tanstack/react-query";
import { AuthRequiredError } from "./api";

// A single QueryClient for the app. A 401 anywhere (query or mutation) means the in-memory credentials are gone
// or rejected: we hand it to onUnauthorized, which clears credentials AND the cache and drops the app back to
// the sign-in gate (OD-13a). Credentials never touch storage, so a refresh naturally lands here too.
let onUnauthorized: () => void = () => {};
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

function handle(error: unknown): void {
  if (error instanceof AuthRequiredError) {
    onUnauthorized();
  }
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handle }),
  mutationCache: new MutationCache({ onError: handle }),
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
  repositories: (page: number, size: number) => ["repositories", page, size] as const,
  repositoriesAll: ["repositories"] as const,
  scanWarnings: (scanId: string, page: number) => ["scan-warnings", scanId, page] as const,
  feed: (params: unknown) => ["feed", params] as const,
  feedAll: ["feed"] as const,
  feedSummary: (params: unknown) => ["feed-summary", params] as const,
  feedSummaryAll: ["feed-summary"] as const,
  group: (groupId: string) => ["issue-group", groupId] as const,
  subIssues: (groupId: string, page: number) => ["sub-issues", groupId, page] as const,
  subIssuesAll: ["sub-issues"] as const,
  subIssueEvents: (subId: string, page: number) => ["sub-issue-events", subId, page] as const,
  ignored: (page: number) => ["ignored", page] as const,
  ignoredAll: ["ignored"] as const,
  ignoreRules: (page: number) => ["ignore-rules", page] as const,
  ignoreRulesAll: ["ignore-rules"] as const,
  dashboard: (includeInactive: boolean) => ["dashboard", includeInactive] as const,
  dashboardAll: ["dashboard"] as const,
  workspaceSettings: ["workspace-settings"] as const,
};

// --- Invalidation map -------------------------------------------------------------------------------
// After a mutation, invalidate every query family a user could see change. Documented here so a new screen
// cannot forget to refresh (R3/R5): any ignore/unignore/settings/scan/EPSS mutation refreshes the panel, the
// feed rows + summary, the Ignored view, the Dashboard and the Repositories rollups.
type MutationKind = "triage" | "settings" | "scan" | "workspaceSettings";

const AFFECTED: Record<MutationKind, readonly (readonly unknown[])[]> = {
  // ignore / unignore / delete-rule: statuses and every count move.
  triage: [qk.subIssuesAll, qk.feedAll, qk.feedSummaryAll, qk.ignoredAll, qk.ignoreRulesAll, qk.dashboardAll,
    qk.repositoriesAll],
  // repo settings: sensitivity re-scores (scores/bands move), include-dev-deps/active change rollups + feed.
  settings: [qk.repositoriesAll, qk.feedAll, qk.feedSummaryAll, qk.dashboardAll, qk.subIssuesAll],
  // start scan / status poll reaching terminal: counts can change, rollups refresh.
  scan: [qk.repositoriesAll, qk.feedAll, qk.feedSummaryAll, qk.dashboardAll],
  // EPSS toggle: LOW_EPSS auto-ignore + scoring tiers move feed/ignored/dashboard counts.
  workspaceSettings: [qk.workspaceSettings, qk.feedAll, qk.feedSummaryAll, qk.ignoredAll, qk.dashboardAll,
    qk.subIssuesAll],
};

export function invalidateFor(kind: MutationKind): Promise<void> {
  return Promise.all(
    AFFECTED[kind].map((key) => queryClient.invalidateQueries({ queryKey: key })),
  ).then(() => undefined);
}
