import { useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api, type RepositoryView } from "../api";
import { qk } from "../queryClient";
import { relativeTime, titleCase } from "../lib/format";
import SeverityBadges from "../components/SeverityBadges";
import ScanButton from "../components/ScanButton";
import RepoSettingsDialog from "../components/RepoSettingsDialog";
import WarningsDrawer from "../components/WarningsDrawer";
import ErrorBanner from "../components/ErrorBanner";

const SIZE = 50;

export default function RepositoriesPage() {
  const [page, setPage] = useState(0);
  const [showInactive, setShowInactive] = useState(true);
  const [editing, setEditing] = useState<RepositoryView | null>(null);
  const [warnings, setWarnings] = useState<{ scanId: string; path: string } | null>(null);

  const repos = useQuery({
    queryKey: qk.repositories(page, SIZE),
    queryFn: () => api.listRepositories(page, SIZE),
  });
  // Server read model for the inactive line (open findings in inactive repos).
  const dash = useQuery({ queryKey: qk.dashboard(false), queryFn: () => api.dashboardSummary(false) });

  const rows = (repos.data?.content ?? []).filter((r) => showInactive || r.active);
  const inactive = dash.data?.inactive;

  return (
    <section>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h1>Repositories</h1>
        <div className="row">
          <Link to="/connect">Connect GitLab</Link>
          <Link to="/select">Select repositories</Link>
        </div>
      </div>

      {inactive && inactive.inactiveRepoCount > 0 ? (
        <p className="muted" data-testid="inactive-line">
          {inactive.inactiveRepoCount} inactive repo{inactive.inactiveRepoCount === 1 ? "" : "s"},{" "}
          {inactive.openFindingsInInactiveRepos} open finding
          {inactive.openFindingsInInactiveRepos === 1 ? "" : "s"} (not scanned)
        </p>
      ) : null}

      <div className="toolbar">
        <label className="row">
          <input
            type="checkbox"
            checked={showInactive}
            onChange={(e) => setShowInactive(e.target.checked)}
          />
          Show inactive
        </label>
      </div>

      {repos.error ? <ErrorBanner error={repos.error} /> : null}

      <table className="table">
        <thead>
          <tr>
            <th>Repository</th>
            <th>Languages</th>
            <th>Open by severity</th>
            <th>Ignored</th>
            <th>Last scan</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>
                <div className="row">
                  <strong>{r.pathWithNamespace}</strong>
                  {!r.active ? <span className="badge badge-inactive">Inactive</span> : null}
                </div>
                {r.warningCount > 0 && r.lastScan ? (
                  <button
                    className="badge badge-inactive"
                    onClick={() => setWarnings({ scanId: r.lastScan!.id, path: r.pathWithNamespace })}
                  >
                    {r.warningCount} warning{r.warningCount === 1 ? "" : "s"}
                  </button>
                ) : null}
              </td>
              <td>{r.languages.join(", ") || "—"}</td>
              <td>
                <SeverityBadges counts={r.openBySeverity} />
              </td>
              <td data-testid={`ignored-${r.gitlabProjectId}`}>{r.ignoredCount}</td>
              <td>
                {r.lastScan ? (
                  <span title={r.lastScan.finishedAt ?? ""}>
                    {titleCase(r.lastScan.status)} · {relativeTime(r.lastScan.finishedAt)}
                  </span>
                ) : (
                  <span className="muted">Never scanned</span>
                )}
              </td>
              <td>
                <div className="row">
                  <ScanButton repositoryId={r.id} disabled={!r.active} />
                  <button onClick={() => setEditing(r)}>Settings</button>
                </div>
              </td>
            </tr>
          ))}
          {rows.length === 0 ? (
            <tr>
              <td colSpan={6} className="muted">
                No repositories. Connect GitLab and select some to scan.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>

      {(repos.data?.totalPages ?? 1) > 1 ? (
        <div className="pager">
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span className="muted">
            Page {page + 1} of {repos.data?.totalPages}
          </span>
          <button
            disabled={page + 1 >= (repos.data?.totalPages ?? 1)}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      ) : null}

      {editing ? <RepoSettingsDialog repo={editing} onClose={() => setEditing(null)} /> : null}
      {warnings ? (
        <WarningsDrawer
          scanId={warnings.scanId}
          repositoryPath={warnings.path}
          onClose={() => setWarnings(null)}
        />
      ) : null}
    </section>
  );
}
