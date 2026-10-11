import { useEffect, useState } from "react";
import { api, Paged, ProjectView } from "../api";

const PAGE_SIZE = 50;

/** Lists the connected group's projects and lets the user select (activate) them. */
export default function SelectRepositories() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Paged<ProjectView> | null>(null);
  const [checked, setChecked] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    api
      .listProjects(page, PAGE_SIZE)
      .then((d) => {
        if (active) setData(d);
      })
      .catch((e) => active && setError(e instanceof Error ? e.message : "Failed to load projects"));
    return () => {
      active = false;
    };
  }, [page]);

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
      const result = await api.selectRepositories([...checked]);
      setSaved(result.length);
      setChecked(new Set());
      const d = await api.listProjects(page, PAGE_SIZE);
      setData(d);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to select repositories");
    } finally {
      setBusy(false);
    }
  }

  if (error) return <p style={{ color: "#b00020" }}>{error}</p>;
  if (!data) return <p>Loading projects…</p>;

  return (
    <section style={{ display: "grid", gap: "0.75rem" }}>
      <h2>Select repositories</h2>
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
    </section>
  );
}
