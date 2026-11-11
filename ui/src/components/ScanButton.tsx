import { useEffect, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { invalidateFor } from "../queryClient";

const TERMINAL = new Set(["completed", "partial", "failed"]);

/**
 * Start Scan + status polling (R1). The POST enqueues a scan; we then poll GET /scans/{id} every 1.5s until it
 * reaches a terminal status, and on terminal refetch the feed/dashboard/repository counts. With no worker (the
 * e2e stack) the scan stays queued — the polling-to-terminal path is exercised by intercepting the status call.
 */
export default function ScanButton({
  repositoryId,
  disabled,
  primary = false,
}: {
  repositoryId: string;
  disabled?: boolean;
  primary?: boolean;
}) {
  const [scanId, setScanId] = useState<string | null>(null);

  const start = useMutation({
    mutationFn: () => api.startScan(repositoryId),
    onSuccess: (scan) => {
      setScanId(scan.id);
      void invalidateFor("scan");
    },
  });

  const poll = useQuery({
    queryKey: ["scan-status", scanId],
    queryFn: () => api.getScan(scanId as string),
    enabled: scanId != null,
    refetchInterval: (query) => {
      const status = query.state.data?.scan.status;
      return status && TERMINAL.has(status) ? false : 1500;
    },
  });

  const status = poll.data?.scan.status;
  useEffect(() => {
    if (status && TERMINAL.has(status)) {
      void invalidateFor("scan");
      setScanId(null);
    }
  }, [status]);

  const scanning = scanId != null;
  const busy = scanning || start.isPending;
  return (
    <button
      className={primary ? "btn btn-primary" : "btn btn-secondary btn-sm"}
      disabled={disabled || busy}
      onClick={() => start.mutate()}
      title={
        disabled
          ? "Inactive repositories cannot be scanned"
          : busy
            ? "A scan is already queued or running"
            : "Start a scan"
      }
    >
      {scanning ? "Scanning…" : start.isPending ? "Starting…" : "Scan"}
    </button>
  );
}
