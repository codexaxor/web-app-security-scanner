import { useState } from "react";
import { Link, useParams } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import { isUuid } from "../lib/validate";
import RelativeTime from "../components/RelativeTime";
import ScanButton from "../components/ScanButton";
import RowMenu from "../components/RowMenu";
import RepoSettingsDialog from "../components/RepoSettingsDialog";
import ErrorBanner from "../components/ErrorBanner";
import { PageHeader, EmptyState, ScanStatusChip } from "../components/ui";

/**
 * The repository page (/repositories/:id). M8.6 item 4. This slice (U3) establishes the route + header (name,
 * group path, branch, last-scan status + age, Scan, ⋮); the coverage banner and the Issues/Scans/Settings
 * tabs are added in U4.
 */
export default function RepositoryPage() {
  const { id } = useParams();
  const [editing, setEditing] = useState(false);
  const valid = isUuid(id);

  const repo = useQuery({
    queryKey: qk.repository(id ?? ""),
    queryFn: () => api.getRepository(id as string),
    enabled: valid,
  });

  if (!valid) {
    return (
      <section>
        <PageHeader title="Repository" />
        <EmptyState title="Page not found" testId="not-found">
          That link isn't valid. <Link to="/repositories">Go to Repositories</Link>.
        </EmptyState>
      </section>
    );
  }

  const r = repo.data;
  return (
    <section>
      <div className="breadcrumb">
        <Link to="/repositories">← Repositories</Link>
      </div>
      {repo.error ? <ErrorBanner error={repo.error} /> : null}
      {r ? (
        <>
          <PageHeader
            title={r.pathWithNamespace.split("/").filter(Boolean).pop() ?? r.pathWithNamespace}
            subtitle={
              <span className="repo-header-sub">
                <span className="mono">{r.pathWithNamespace}</span>
                {r.effectiveBranch ? <span className="muted branch-label">· {r.effectiveBranch}</span> : null}
                {!r.active ? <span className="chip chip-ignored">Inactive</span> : null}
              </span>
            }
            actions={
              <>
                <ScanButton repositoryId={r.id} disabled={!r.active} />
                <RowMenu label="Repository actions">
                  {(close) => (
                    <button
                      type="button"
                      className="rowmenu-item"
                      onClick={() => {
                        close();
                        setEditing(true);
                      }}
                    >
                      Settings
                    </button>
                  )}
                </RowMenu>
              </>
            }
          />
          <div className="repo-lastscan muted">
            {r.lastScan ? (
              <span className="row">
                Last scan: <ScanStatusChip status={r.lastScan.status} />{" "}
                <RelativeTime iso={r.lastScan.finishedAt} />
              </span>
            ) : (
              <span>Never scanned</span>
            )}
          </div>
        </>
      ) : null}

      {editing && r ? <RepoSettingsDialog repo={r} onClose={() => setEditing(false)} /> : null}
    </section>
  );
}
