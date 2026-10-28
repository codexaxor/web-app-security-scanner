import { useQuery, type UseQueryResult } from "@tanstack/react-query";

/**
 * Live scan-status polling (M8.8 item 5 / SF-3). While any queued/running scan is visible in a list, that list
 * polls every 3 s; it stops when none are active, and — crucially — it PAUSES while the tab is hidden and
 * RESUMES when the tab becomes visible again. That pause/resume is react-query's own behaviour:
 * {@code refetchIntervalInBackground: false} clears the interval while the document is hidden
 * (visibilitychange) and restarts it on refocus. We only decide whether a scan is active.
 */
export const POLL_MS = 3000;

/** A scan that has not finished — its row keeps the list polling. */
export const ACTIVE_STATUSES: ReadonlySet<string> = new Set(["queued", "running"]);

export function anyActive(statuses: ReadonlyArray<string | null | undefined>): boolean {
  return statuses.some((s) => s != null && ACTIVE_STATUSES.has(s));
}

/**
 * A query that polls every {@link POLL_MS} while {@code isActive(data)} holds, pausing when the tab is hidden and
 * resuming when it is visible (SF-3). Takes the minimal query params so the generics stay simple.
 */
export function useScanPolling<T>(
  params: { queryKey: readonly unknown[]; queryFn: () => Promise<T>; enabled?: boolean },
  isActive: (data: T | undefined) => boolean,
): UseQueryResult<T> {
  return useQuery({
    queryKey: params.queryKey,
    queryFn: params.queryFn,
    enabled: params.enabled,
    refetchInterval: (query) => (isActive(query.state.data) ? POLL_MS : false),
    refetchIntervalInBackground: false,
  });
}
