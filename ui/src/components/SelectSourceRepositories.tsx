import { useEffect, useState } from "react";
import { api, ConnectionView, Paged, SourceRepoView } from "../api";
import Button from "./Button";
import RepoName from "./RepoName";
import ProviderBadge from "./ProviderBadge";
import { providerMeta } from "../lib/providerMeta";
import { EmptyState } from "./ui";

const PAGE_SIZE = 50;

/**
 * SF-1: the provider-neutral "Select repositories" step for a GitHub / public connection. It lists the
 * connection's repositories via {@code listSourceRepositories} and selects them via
 * {@code selectSourceRepositories}, keyed on the provider's repository id (a string, unlike the GitLab-only
 * {@link SelectRepositories} which keys on the numeric project id). A single-repository or public connection
 * exposes exactly one repository, which is preselected so adding it is a single click.
 */
export default function SelectSourceRepositories({
  connection,
  onSelected,
}: {
  connection: ConnectionView;
  onSelected: () => void;
}) {
  // A single GitHub repo or a public repo targets one repository → preselect it (one click to add).
  const single = connection.targetType === "repo";
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [data, setData] = useState<Paged<SourceRepoView> | null>(null);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [added, setAdded] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    setData(null);
    api
      .listSourceRepositories(connection.id, search, page, PAGE_SIZE)
      .then((d) => {
        if (!active) return;
        setData(d);
        // Preselect the one selectable repository of a single-repo / public connection (one click to add).
        if (single && page === 0 && !search) {
          setChecked(new Set(d.content.filter((r) => !r.notSelectableReason).map((r) => r.providerRepoId)));
        }
      })
      .catch((e) => active && setError(e instanceof Error ? e.message : "Failed to load repositories"));
    return () => {
      active = false;
    };
  }, [connection.id, page, search, single]);

  function toggle(id: string) {
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function add() {
    setBusy(true);
    setError(null);
    setAdded(null);
    try {
      const result = await api.selectSourceRepositories(connection.id, [...checked]);
      setAdded(result.length);
      setChecked(new Set());
      onSelected();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to select repositories");
    } finally {
      setBusy(false);
    }
  }

  const m = providerMeta(connection.provider);

  return (
    <div className="stack" data-testid="select-source">
      <div className="row">
        <ProviderBadge provider={connection.provider} />
        <h3 className="section-head" style={{ margin: 0 }}>
          Select repositories to scan
        </h3>
      </div>
      <p className="muted">
        {single
          ? `The ${m.label} repository below is preselected — add it to start scanning.`
          : `Choose the ${m.label} repositories this connection should scan.`}
      </p>

      {!single ? (
        <div className="search-field">
          <input
            type="search"
            aria-label="Search source repositories"
            placeholder="Search by name"
            value={search}
            onChange={(e) => {
              setPage(0);
              setSearch(e.target.value);
            }}
          />
        </div>
      ) : null}

      {error ? <p className="field-error">{error}</p> : null}

      {!data ? (
        <p className="muted">Loading repositories…</p>
      ) : data.content.length === 0 ? (
        <EmptyState title="No repositories found">This connection exposes no repositories to select.</EmptyState>
      ) : (
        <>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th></th>
                  <th>Repository</th>
                  <th>Default branch</th>
                  <th>Visibility</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((r) => (
                  <tr key={r.providerRepoId} data-testid="source-repo-row">
                    <td>
                      <input
                        type="checkbox"
                        aria-label={`Select ${r.fullName}`}
                        disabled={!!r.notSelectableReason}
                        title={r.notSelectableReason ?? undefined}
                        checked={checked.has(r.providerRepoId)}
                        onChange={() => toggle(r.providerRepoId)}
                      />
                    </td>
                    <td>
                      <RepoName path={r.fullName} showPath />
                    </td>
                    <td>{r.defaultBranch ?? "—"}</td>
                    <td className="muted">{r.visibility ?? "—"}</td>
                    <td>
                      {r.notSelectableReason ? (
                        <span className="expiry-warn">{r.notSelectableReason}</span>
                      ) : r.empty ? (
                        <span className="muted">Empty</span>
                      ) : (
                        <span className="muted">Selectable</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="row">
            {!single ? (
              <>
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
              </>
            ) : null}
            <Button
              variant="primary"
              disabled={busy || checked.size === 0}
              onClick={add}
              data-testid="add-source-repos"
            >
              Add {checked.size || ""} selected
            </Button>
            {added !== null ? (
              <span className="ok-text" data-testid="source-repos-added">
                {added} {added === 1 ? "repository" : "repositories"} added to the Repositories list.
              </span>
            ) : null}
          </div>
        </>
      )}
    </div>
  );
}
