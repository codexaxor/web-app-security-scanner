import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import ErrorBanner from "./ErrorBanner";

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
    <div className="overlay" onClick={onClose}>
      <aside className="panel" role="dialog" aria-label="Scan warnings" onClick={(e) => e.stopPropagation()}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2>Scan warnings — {repositoryPath}</h2>
          <button onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        {q.error ? <ErrorBanner error={q.error} /> : null}
        {q.isLoading ? <p className="muted">Loading…</p> : null}
        {q.data && q.data.content.length === 0 ? <p className="muted">No warnings for this scan.</p> : null}
        <ul className="stack" style={{ listStyle: "none", padding: 0 }}>
          {q.data?.content.map((w) => (
            <li key={w.id} className="subissue">
              <div className="row">
                <span className="badge badge-inactive">{w.code}</span>
                {w.filePath ? <code>{w.filePath}</code> : null}
              </div>
              <div>{w.message}</div>
            </li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
