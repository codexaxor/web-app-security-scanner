import { useEffect, useState } from "react";
import { api, ConnectionView, Paged, ProjectView, RepositoryView } from "../api";
import Button from "./Button";
import RepoName from "./RepoName";
import { EmptyState } from "./ui";

const PAGE_SIZE = 50;

/**
 * Pick a connection, list the projects it exposes (a group's projects, or its single project) and select them.
 * Below, the workspace's selected repositories with the connection that reaches each one and any inactive reason.
 */
export default function SelectRepositories() {
  const [connections, setConnections] = useState<ConnectionView[]>([]);
  const [connectionId, setConnectionId] = useState<string>("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Paged<ProjectView> | null>(null);
  const [checked, setChecked] = useState<Set<number>>(new Set());
  const [selected, setSelected] = useState<RepositoryView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState<number | null>(null);

  useEffect(() => {
    api
      .listConnections(0, 100)
      .then((c) => {
        setConnections(c.content);
        if (c.content.length > 0) setConnectionId((prev) => prev || c.content[0].id);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load connections"));
    refreshSelected();
  }, []);

  useEffect(() => {
    if (!connectionId) return;
    let active = true;
    setData(null);
    api
      .listProjects(connectionId, page, PAGE_SIZE)
      .then((d) => active && setData(d))
      .catch((e) => active && setError(e instanceof Error ? e.message : "Failed to load projects"));
    return () => {
      active = false;
    };
  }, [connectionId, page]);

  function refreshSelected() {
    api
      .listRepositories(0, 500)
      .then((r) => setSelected(r.content))
      .catch(() => setSelected([]));
  }

  function toggle(id: number) {
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function select() {
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const result = await api.selectRepositories(connectionId, [...checked]);
      setSaved(result.length);
      setChecked(new Set());
      const d = await api.listProjects(connectionId, page, PAGE_SIZE);
      setData(d);
      refreshSelected();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to select repositories");
    } finally {
      setBusy(false);
    }
  }

  const label = (c: ConnectionView) =>
    `${c.targetType}: ${c.targetType === "group" ? c.groupFullPath : c.projectFullPath}`;

  if (error) return <p className="field-error">{error}</p>;
  if (connections.length === 0) return <EmptyState title="Add a GitLab connection first" />;

  return (
    <div className="stack-4 onboarding-wide">
      <div className="stack-4">
        <div className="field">
          <label htmlFor="sel-conn">Connection</label>
          <select
            id="sel-conn"
            value={connectionId}
            onChange={(e) => {
              setConnectionId(e.target.value);
              setPage(0);
              setChecked(new Set());
            }}
          >
            {connections.map((c) => (
              <option key={c.id} value={c.id}>
                {label(c)}
              </option>
            ))}
          </select>
        </div>

        {!data ? (
          <p className="muted">Loading projects…</p>
        ) : (
          <>
            <p className="muted">
              {data.totalElements} projects · page {data.page + 1} of {Math.max(1, data.totalPages)}
            </p>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th></th>
                    <th>Project</th>
                    <th>Default branch</th>
                    <th>Languages</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((p) => (
                    <tr key={p.gitlabProjectId}>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`Select ${p.pathWithNamespace}`}
                          disabled={!p.selectable}
                          checked={checked.has(p.gitlabProjectId)}
                          onChange={() => toggle(p.gitlabProjectId)}
                        />
                      </td>
                      <td>
                        <RepoName path={p.pathWithNamespace} showPath />
                      </td>
                      <td>{p.defaultBranch ?? "—"}</td>
                      <td>{p.languages.length ? p.languages.join(", ") : <span className="muted">Not detected</span>}</td>
                      <td>
                        {!p.selectable && <span className="expiry-warn">{p.notSelectableReason}</span>}
                        {p.selectable && p.active && <span className="chip chip-solved">Selected</span>}
                        {p.selectable && p.alreadySelected && !p.active && (
                          <span className="chip chip-ignored">Inactive</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="row">
              <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Prev
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
              <Button variant="primary" disabled={busy || checked.size === 0} onClick={select}>
                Add {checked.size || ""} selected
              </Button>
              {saved !== null && <span className="ok-text">Added {saved} repositories.</span>}
            </div>
          </>
        )}
      </div>

      <div className="stack">
        <h2 className="section-head">Selected repositories</h2>
        {selected.length === 0 ? (
          <EmptyState title="None selected yet">Pick projects above to start scanning them.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Repository</th>
                  <th>Connection</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {selected.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <RepoName path={r.pathWithNamespace} showPath />
                    </td>
                    <td>{r.connectionLabel ?? <span className="muted">none</span>}</td>
                    <td>
                      {r.active ? (
                        <span className="chip chip-solved">Active</span>
                      ) : (
                        <span className="chip chip-ignored">
                          Inactive{r.inactiveReason ? ` (${r.inactiveReason})` : ""}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
