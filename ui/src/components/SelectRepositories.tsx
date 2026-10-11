import { useEffect, useState } from "react";
import { api, ConnectionView, Paged, ProjectView, RepositoryView } from "../api";

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

  if (error) return <p style={{ color: "#b00020" }}>{error}</p>;
  if (connections.length === 0) return <p>Add a GitLab connection first.</p>;

  return (
    <section style={{ display: "grid", gap: "1.5rem" }}>
      <div style={{ display: "grid", gap: "0.75rem" }}>
        <h2>Select repositories</h2>
        <label>
          Connection{" "}
          <select
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
        </label>

        {!data ? (
          <p>Loading projects…</p>
        ) : (
          <>
            <p style={{ margin: 0, color: "#555" }}>
              {data.totalElements} projects · page {data.page + 1} of {Math.max(1, data.totalPages)}
            </p>
            <table style={{ borderCollapse: "collapse", width: "100%" }}>
              <thead>
                <tr style={{ textAlign: "left", borderBottom: "1px solid #ccc" }}>
                  <th></th>
                  <th>Project</th>
                  <th>Default branch</th>
                  <th>Languages</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((p) => (
                  <tr key={p.gitlabProjectId} style={{ borderBottom: "1px solid #eee" }}>
                    <td>
                      <input
                        type="checkbox"
                        disabled={!p.selectable}
                        checked={checked.has(p.gitlabProjectId)}
                        onChange={() => toggle(p.gitlabProjectId)}
                      />
                    </td>
                    <td>{p.pathWithNamespace}</td>
                    <td>{p.defaultBranch ?? "—"}</td>
                    <td>{p.languages.join(", ") || "—"}</td>
                    <td>
                      {!p.selectable && <span style={{ color: "#b06f00" }}>{p.notSelectableReason}</span>}
                      {p.selectable && p.active && <span style={{ color: "#0a7d33" }}>selected</span>}
                      {p.selectable && p.alreadySelected && !p.active && (
                        <span style={{ color: "#777" }}>inactive</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
              <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Prev
              </button>
              <button disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
                Next
              </button>
              <button disabled={busy || checked.size === 0} onClick={select}>
                Add {checked.size || ""} selected
              </button>
              {saved !== null && <span style={{ color: "#0a7d33" }}>Added {saved} repositories.</span>}
            </div>
          </>
        )}
      </div>

      <div style={{ display: "grid", gap: "0.5rem" }}>
        <h3 style={{ margin: 0 }}>Selected repositories</h3>
        {selected.length === 0 ? (
          <p style={{ color: "#555" }}>None selected yet.</p>
        ) : (
          <table style={{ borderCollapse: "collapse", width: "100%" }}>
            <thead>
              <tr style={{ textAlign: "left", borderBottom: "1px solid #ccc" }}>
                <th>Repository</th>
                <th>Connection</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {selected.map((r) => (
                <tr key={r.id} style={{ borderBottom: "1px solid #eee" }}>
                  <td>{r.pathWithNamespace}</td>
                  <td>{r.connectionLabel ?? <span style={{ color: "#777" }}>none</span>}</td>
                  <td>
                    {r.active ? (
                      <span style={{ color: "#0a7d33" }}>active</span>
                    ) : (
                      <span style={{ color: "#b06f00" }}>inactive{r.inactiveReason ? ` (${r.inactiveReason})` : ""}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
