import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import Button from "./Button";
import RelativeTime from "./RelativeTime";

/**
 * A searchable "Branch to scan" combobox (D-076, A3). The first option is "Default branch (<name>)" which follows
 * the GitLab default (value = null); the rest are the project's branches, fetched server-side with a `search`
 * filter and pagination so it works past 100 branches. Branch names are external data, rendered as text only
 * (D-067). The default and protected branches are marked with chips.
 */
export default function BranchPicker({
  repoId,
  defaultBranch,
  value,
  onChange,
}: {
  repoId: string;
  defaultBranch: string | null;
  value: string | null;
  onChange: (branch: string | null) => void;
}) {
  const [search, setSearch] = useState("");
  const [debounced, setDebounced] = useState("");
  const [page, setPage] = useState(0);

  // N-6: debounce the server-side search (~300 ms) so typing does not fire a request per keystroke.
  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const q = useQuery({
    queryKey: ["repo-branches", repoId, debounced, page],
    queryFn: () => api.listRepositoryBranches(repoId, debounced, page, 100),
  });

  const defaultLabel = `Default branch${defaultBranch ? ` (${defaultBranch})` : ""}`;

  return (
    <div className="field">
      <label htmlFor="branch-search">Branch to scan</label>
      <input
        id="branch-search"
        type="search"
        placeholder="Search branches…"
        autoComplete="off"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
      />
      <div className="branch-list" role="listbox" aria-label="Branch to scan">
        <button
          type="button"
          role="option"
          aria-selected={value === null}
          className={`branch-option${value === null ? " is-selected" : ""}`}
          onClick={() => onChange(null)}
        >
          {defaultLabel}
        </button>
        {q.isLoading ? <p className="muted">Loading branches…</p> : null}
        {q.error ? <p className="field-error">Could not load branches.</p> : null}
        {q.data?.content.map((b) => (
          <button
            type="button"
            role="option"
            key={b.name}
            aria-selected={value === b.name}
            className={`branch-option${value === b.name ? " is-selected" : ""}`}
            onClick={() => onChange(b.name)}
          >
            <span className="branch-name">{b.name}</span>
            {b.isDefault ? <span className="chip chip-solved">default</span> : null}
            {b.isProtected ? <span className="chip chip-ignored">protected</span> : null}
            {/* Last-commit time only when the provider gave one (GitHub gives none) — never "Invalid date"/a dash. */}
            {b.lastCommitAt ? (
              <span className="muted branch-commit">
                <RelativeTime iso={b.lastCommitAt} />
              </span>
            ) : null}
          </button>
        ))}
      </div>
      {q.data?.searchTruncated ? (
        <p className="muted">Showing matches from the first 1,000 branches. Refine your search to narrow it.</p>
      ) : null}
      {q.data && q.data.totalPages > 1 ? (
        <div className="row">
          <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Prev
          </Button>
          <span className="muted">
            page {q.data.page + 1} of {q.data.totalPages} · {q.data.totalElements} branches
          </span>
          <Button
            variant="secondary"
            size="sm"
            disabled={page + 1 >= q.data.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </Button>
        </div>
      ) : null}
    </div>
  );
}
