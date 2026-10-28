import { useEffect, useRef } from "react";
import type { RepositoryView } from "../api";
import { invalidateFor } from "../queryClient";
import { useToast } from "../toast";
import { ACTIVE_STATUSES } from "./scanPolling";

/**
 * Watches a repositories list across poll cycles (M8.8 item 5) and, when a repository's scan transitions from
 * active (queued/running) to completed, shows a toast "Scan of <repo> completed · N open" and invalidates the
 * counts/issue lists so the numbers update without a reload. Transitions to failed/partial are left to their own
 * surfaces (the row's status chip + warnings), not toasted here.
 */
export function useScanCompletionToasts(rows: RepositoryView[]): void {
  const toast = useToast();
  const prevActive = useRef<Set<string>>(new Set());

  useEffect(() => {
    const nowActive = new Set(
      rows.filter((r) => r.lastScan != null && ACTIVE_STATUSES.has(r.lastScan.status)).map((r) => r.id),
    );
    for (const r of rows) {
      const wasActive = prevActive.current.has(r.id);
      const stillActive = nowActive.has(r.id);
      if (wasActive && !stillActive && r.lastScan?.status === "completed") {
        const open =
          r.openBySeverity.critical + r.openBySeverity.high + r.openBySeverity.medium + r.openBySeverity.low;
        toast.success(`Scan of ${r.pathWithNamespace} completed · ${open} open`);
        void invalidateFor("scan");
      }
    }
    prevActive.current = nowActive;
  }, [rows, toast]);
}
