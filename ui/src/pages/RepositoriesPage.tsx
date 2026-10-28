import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api, type RepositoryView, type RepoSort } from "../api";
import { qk } from "../queryClient";
import RelativeTime from "../components/RelativeTime";
import RepoName from "../components/RepoName";
import SeverityChips from "../components/SeverityChips";
import ScanButton from "../components/ScanButton";
import RowMenu from "../components/RowMenu";
import RepoSettingsDialog from "../components/RepoSettingsDialog";
import WarningsDrawer from "../components/WarningsDrawer";
import ErrorBanner from "../components/ErrorBanner";
import Icon from "../components/icons";
import { TableSkeleton } from "../components/Skeleton";
import { PageHeader, EmptyState, ScanStatusChip } from "../components/ui";

const SIZE = 50;
const COLUMNS = 6;

const SORTS: { value: RepoSort; label: string }[] = [
  { value: "severity", label: "Severity" },
  { value: "name", label: "Name" },
  { value: "lastscan", label: "Last scan" },
];

export default function RepositoriesPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState<RepoSort>("severity");
  const [showInactive, setShowInactive] = useState(false);
  const [hasOpenIssues, setHasOpenIssues] = useState(false);
  const [lastScanFailed, setLastScanFailed] = useState(false);
  const [editing, setEditing] = useState<RepositoryView | null>(null);
  const [warnings, setWarnings] = useState<{ scanId: string; path: string } | null>(null);

  const params = {
    search: search.trim() || undefined,
    includeInactive: showInactive,
    hasOpenIssues,
    lastScanFailed,
    sort,
    page,
    size: SIZE,
  };

  function resetAnd(fn: () => void) {
    setPage(0);
    fn();
  }

  const repos = useQuery({
    queryKey: qk.repositories(params),
    queryFn: () => api.listRepositories(params),
  });
  // Stable "N active repositories" headline — the default (active-only, no filters) total, independent of the
  // current search/filter view.
  const active = useQuery({
    queryKey: qk.repositories({ activeCount: true }),
    queryFn: () => api.listRepositories({ size: 1 }),
  });
  const dash = useQuery({ queryKey: qk.dashboard(false), queryFn: () => api.dashboardSummary(false) });

  const rows = repos.data?.content ?? [];
  const inactive = dash.data?.inactive;
  const activeCount = active.data?.totalElements;

  return (
    <section>
      <PageHeader
        title="Repositories"
        subtitle={
          activeCount != null
            ? `${activeCount} active ${activeCount === 1 ? "repository" : "repositories"}`
            : "Repositories selected for scanning."
        }
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
        <div className="search-field">
          <Icon name="search" className="search-icon" />
          <input
            type="search"
            aria-label="Search repositories"
            placeholder="Search name or path"
            value={search}
            onChange={(e) => resetAnd(() => setSearch(e.target.value))}
          />
        </div>
        <select aria-label="Sort" value={sort} onChange={(e) => resetAnd(() => setSort(e.target.value as RepoSort))}>
          {SORTS.map((s) => (
            <option key={s.value} value={s.value}>
              Sort: {s.label}
            </option>
          ))}
        </select>
        <label className="check">
          <input
            type="checkbox"
            checked={showInactive}
            onChange={(e) => resetAnd(() => setShowInactive(e.target.checked))}
          />
          Show inactive
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={hasOpenIssues}
            onChange={(e) => resetAnd(() => setHasOpenIssues(e.target.checked))}
          />
          Has open issues
        </label>
        <label className="check">
          <input
            type="checkbox"
            checked={lastScanFailed}
            onChange={(e) => resetAnd(() => setLastScanFailed(e.target.checked))}
          />
          Last scan failed
        </label>
      </div>

      {repos.error ? <ErrorBanner error={repos.error} /> : null}

      {rows.length === 0 && !repos.isLoading ? (
        <EmptyState title="No repositories match">
          {search || hasOpenIssues || lastScanFailed
            ? "Clear a filter or search to see more repositories."
            : "Connect GitLab and select repositories to start scanning."}
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
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            {repos.isLoading ? (
              <TableSkeleton columns={COLUMNS} />
            ) : (
              <tbody>
                {rows.map((r) => (
                  <tr
                    key={r.id}
                    className="row-click"
                    tabIndex={0}
                    data-testid="repo-row"
                    onClick={() => navigate(`/repositories/${r.id}`)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        navigate(`/repositories/${r.id}`);
                      }
                    }}
                  >
                    <td>
                      <div className="row">
                        <RepoName path={r.pathWithNamespace} showPath />
                        {r.effectiveBranch ? (
                          <span className="muted branch-label">· {r.effectiveBranch}</span>
                        ) : null}
                        {!r.active ? <span className="chip chip-ignored">Inactive</span> : null}
                      </div>
                      {r.branchMissing ? (
                        <span className="expiry-warn">branch missing — choose another</span>
                      ) : null}
                      {r.warningCount > 0 && r.lastScan ? (
                        <button
                          className="btn btn-ghost btn-sm chip-warn-link"
                          onClick={(e) => {
                            e.stopPropagation();
                            setWarnings({ scanId: r.lastScan!.id, path: r.pathWithNamespace });
                          }}
                        >
                          {r.warningCount} warning{r.warningCount === 1 ? "" : "s"}
                        </button>
                      ) : null}
                    </td>
                    <td>
                      {r.languages.length ? (
                        r.languages.join(", ")
                      ) : (
                        <span className="muted">Not detected</span>
                      )}
                    </td>
                    <td>
                      <SeverityChips counts={r.openBySeverity} hideWhenEmpty />
                    </td>
                    <td className="cell-num" data-testid={`ignored-${r.gitlabProjectId}`}>
                      {r.ignoredCount}
                    </td>
                    <td>
                      {r.lastScan ? (
                        <span className="row">
                          <ScanStatusChip status={r.lastScan.status} />{" "}
                          <RelativeTime iso={r.lastScan.finishedAt} />
                        </span>
                      ) : (
                        <span className="muted">Never scanned</span>
                      )}
                    </td>
                    <td className="cell-actions">
                      <RowMenu>
                        {(close) => (
                          <>
                            <div className="rowmenu-item-wrap">
                              <ScanButton repositoryId={r.id} disabled={!r.active} />
                            </div>
                            <button
                              type="button"
                              className="rowmenu-item"
                              onClick={() => {
                                close();
                                setEditing(r);
                              }}
                            >
                              Settings
                            </button>
                          </>
                        )}
                      </RowMenu>
                    </td>
                  </tr>
                ))}
              </tbody>
            )}
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
