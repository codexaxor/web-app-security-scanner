import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import ErrorBanner from "./ErrorBanner";

// Minimal side panel (S5): lists a group's sub-issues with their key fields. S6 adds ignore/unignore, the full
// score breakdown, EPSS attribution, KEV badge, secret preview and per-sub-issue history.
export default function SubIssuePanel({ groupId, onClose }: { groupId: string; onClose: () => void }) {
  const q = useQuery({
    queryKey: qk.subIssues(groupId, 0),
    queryFn: () => api.listSubIssues(groupId, 0, 100),
  });

  return (
    <div className="overlay" onClick={onClose}>
      <aside className="panel" role="dialog" aria-label="Issue details" onClick={(e) => e.stopPropagation()}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2>Issue details</h2>
          <button onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        {q.error ? <ErrorBanner error={q.error} /> : null}
        {q.isLoading ? <p className="muted">Loading…</p> : null}
        {q.data?.content.map((s) => (
          <div key={s.id} className="subissue" data-testid="subissue">
            <div className="row">
              <strong>{s.vulnId ?? s.ruleId ?? s.packageName ?? s.type}</strong>
              {s.score != null ? <span className="badge sev-high">{s.score}</span> : null}
            </div>
            {s.filePath ? <div className="muted">{s.filePath}</div> : null}
          </div>
        ))}
      </aside>
    </div>
  );
}
