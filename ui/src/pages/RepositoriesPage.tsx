import { useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api, type RepositoryView } from "../api";
import { qk } from "../queryClient";
import RelativeTime from "../components/RelativeTime";
import RepoName from "../components/RepoName";
import SeverityBadges from "../components/SeverityBadges";
import ScanButton from "../components/ScanButton";
import RepoSettingsDialog from "../components/RepoSettingsDialog";
import WarningsDrawer from "../components/WarningsDrawer";
import ErrorBanner from "../components/ErrorBanner";
import Button from "../components/Button";
import StatusChip from "../components/StatusChip";
import { PageHeader, EmptyState } from "../components/ui";

const SIZE = 50;

const scanChipKind = (status: string) =>
  status === "completed" ? "solved" : status === "failed" || status === "partial" ? "ignored" : "todo";

export default function RepositoriesPage() {
  const [page, setPage] = useState(0);
  const [showInactive, setShowInactive] = useState(false);
  const [editing, setEditing] = useState<RepositoryView | null>(null);
  const [warnings, setWarnings] = useState<{ scanId: string; path: string } | null>(null);

  const repos = useQuery({
    queryKey: qk.repositories(page, SIZE),
    queryFn: () => api.listRepositories(page, SIZE),
  });
  const dash = useQuery({ queryKey: qk.dashboard(false), queryFn: () => api.dashboardSummary(false) });

  const rows = (repos.data?.content ?? []).filter((r) => showInactive || r.active);
  const inactive = dash.data?.inactive;

  return (
    <section>
      <PageHeader
        title="Repositories"
        subtitle="Repositories selected for scanning."
        actions={
          <>
            <Link className="btn btn-secondary" to="/connect">
              Connect GitLab
            </Link>
            <Link className="btn btn-primary" to="/select">
              Select repositories
            </Link>
          </>
        }
      />

      {inactive && inactive.inactiveRepoCount > 0 ? (
        <p className="muted inactive-note" data-testid="inactive-line">
          {inactive.inactiveRepoCount} inactive repo{inactive.inactiveRepoCount === 1 ? "" : "s"},{" "}
          {inactive.openFindingsInInactiveRepos} open finding
          {inactive.openFindingsInInactiveRepos === 1 ? "" : "s"} (not scanned)
        </p>
      ) : null}

      <div className="toolbar">
        <label className="check">
          <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} />
          Show inactive
        </label>
      </div>

      {repos.error ? <ErrorBanner error={repos.error} /> : null}

      {rows.length === 0 && !repos.isLoading ? (
        <EmptyState title="No repositories">
          Connect GitLab and select repositories to start scanning.
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Repository</th>
                <th>Languages</th>
                <th>Open by severity</th>
                <th className="cell-num">Ignored</th>
                <th>Last scan</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <div className="row">
                      <RepoName path={r.pathWithNamespace} showPath />
                      {!r.active ? <span className="chip chip-ignored">Inactive</span> : null}
                    </div>
                    {r.warningCount > 0 && r.lastScan ? (
                      <button
                        className="btn btn-ghost btn-sm chip-warn-link"
                        onClick={() => setWarnings({ scanId: r.lastScan!.id, path: r.pathWithNamespace })}
                      >
                        {r.warningCount} warning{r.warningCount === 1 ? "" : "s"}
                      </button>
                    ) : null}
                  </td>
                  <td>{r.languages.length ? r.languages.join(", ") : <span className="muted">Not detected</span>}</td>
                  <td>
                    <SeverityBadges counts={r.openBySeverity} />
                  </td>
                  <td className="cell-num" data-testid={`ignored-${r.gitlabProjectId}`}>
                    {r.ignoredCount}
                  </td>
                  <td>
                    {r.lastScan ? (
                      <span className="row">
                        <StatusChip kind={scanChipKind(r.lastScan.status)} /> <RelativeTime iso={r.lastScan.finishedAt} />
                      </span>
                    ) : (
                      <span className="muted">Never scanned</span>
                    )}
                  </td>
                  <td>
                    <div className="row">
                      <ScanButton repositoryId={r.id} disabled={!r.active} />
                      <Button variant="ghost" size="sm" onClick={() => setEditing(r)}>
                        Settings
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {(repos.data?.totalPages ?? 1) > 1 ? (
        <div className="pager">
          <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous
          </button>
          <span>
            Page {page + 1} of {repos.data?.totalPages}
          </span>
          <button
            className="btn btn-secondary btn-sm"
            disabled={page + 1 >= (repos.data?.totalPages ?? 1)}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      ) : null}

      {editing ? <RepoSettingsDialog repo={editing} onClose={() => setEditing(null)} /> : null}
      {warnings ? (
        <WarningsDrawer scanId={warnings.scanId} repositoryPath={warnings.path} onClose={() => setWarnings(null)} />
      ) : null}
    </section>
  );
}
