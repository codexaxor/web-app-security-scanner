import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import InlineError from "./InlineError";
import Dialog from "./Dialog";
import RepoName from "./RepoName";

/** Lists one scan's scan-health warnings (OD-14 item 2) in a side drawer. Text only. */
export default function WarningsDrawer({
  scanId,
  repositoryPath,
  onClose,
}: {
  scanId: string;
  repositoryPath: string;
  onClose: () => void;
}) {
  const q = useQuery({
    queryKey: qk.scanWarnings(scanId, 0),
    queryFn: () => api.listScanWarnings(scanId, 0, 100),
  });

  return (
    <Dialog title="Scan warnings" onClose={onClose} variant="drawer">
      <div className="panel-header">
        <div className="row-between">
          <div>
            <h2>Scan warnings</h2>
            <div className="muted">
              <RepoName path={repositoryPath} />
            </div>
          </div>
          <button className="panel-close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
      </div>
      <div className="panel-body stack">
        {q.error ? <InlineError error={q.error} /> : null}
        {q.isLoading ? <p className="muted">Loading…</p> : null}
        {q.data && q.data.content.length === 0 ? <p className="muted">No warnings for this scan.</p> : null}
        {q.data?.content.map((w) => (
          <div key={w.id} className="location">
            <div className="row">
              <span className="type-badge">{w.code}</span>
              {w.filePath ? <code>{w.filePath}</code> : null}
            </div>
            <div>{w.message}</div>
          </div>
        ))}
      </div>
    </Dialog>
  );
}
