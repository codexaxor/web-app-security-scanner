/**
 * Live scan-status polling (M8.8 item 5). While any queued/running scan is visible in a list, that list polls
 * every 3s; it stops when none are active, and pauses while the tab is hidden. These are pure helpers so the
 * start/stop/pause decision is deterministically unit-tested; the views pass them to react-query's
 * `refetchInterval` (with `refetchIntervalInBackground: true`, so this is the sole authority on pausing).
 */
export const POLL_MS = 3000;

/** A scan that has not finished — its row keeps the list polling. */
export const ACTIVE_STATUSES: ReadonlySet<string> = new Set(["queued", "running"]);

export function anyActive(statuses: ReadonlyArray<string | null | undefined>): boolean {
  return statuses.some((s) => s != null && ACTIVE_STATUSES.has(s));
}

/**
 * The refetch interval for a list given its current scan statuses and whether the tab is hidden: 3s while a scan
 * is active and the tab is visible (start), false when none are active (stop) or the tab is hidden (pause).
 */
export function scanRefetchInterval(
  statuses: ReadonlyArray<string | null | undefined>,
  hidden: boolean,
): number | false {
  return !hidden && anyActive(statuses) ? POLL_MS : false;
}

/** Whether the document is currently hidden (guarded for non-DOM test environments). */
export function documentHidden(): boolean {
  return typeof document !== "undefined" && document.hidden;
}
