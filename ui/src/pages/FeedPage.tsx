import { useSearchParams, useNavigate, useParams } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api, type FeedParams, type IssueType, type Band, type View } from "../api";
import { qk } from "../queryClient";
import { fixTime, titleCase } from "../lib/format";
import ErrorBanner from "../components/ErrorBanner";
import SubIssuePanel from "../components/SubIssuePanel";

const SIZE = 50;
const TYPES: IssueType[] = ["sca", "sast", "secret", "iac", "eol"];
const BANDS: Band[] = ["critical", "high", "medium", "low"];
const TYPE_LABEL: Record<IssueType, string> = {
  sca: "SCA",
  sast: "SAST",
  secret: "Secret",
  iac: "IaC",
  eol: "EOL",
};

function withinSevenDays(iso: string): boolean {
  return Date.now() - new Date(iso).getTime() < 7 * 86400 * 1000;
}

export default function FeedPage() {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const { groupId } = useParams();

  const view = (params.get("view") as View) || "focus";
  const band = (params.get("band") as Band) || undefined;
  const type = (params.get("type") as IssueType) || undefined;
  const repositoryId = params.get("repositoryId") || undefined;
  const includeInactive = params.get("includeInactive") === "true";
  const page = Number(params.get("page") || "0");

  const filters: FeedParams = { view, band, type, repositoryId, includeInactive };

  function setFilter(key: string, value: string | null) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page"); // any filter change resets to page 0
    setParams(next, { replace: false });
  }

  const feed = useQuery({
    queryKey: qk.feed({ ...filters, page }),
    queryFn: () => api.listFeed({ ...filters, page, size: SIZE }),
  });
  const summary = useQuery({
    queryKey: qk.feedSummary(filters),
    queryFn: () => api.feedSummary(filters),
  });
  const repos = useQuery({ queryKey: qk.repositories(0, 100), queryFn: () => api.listRepositories(0, 100) });

  const s = summary.data;
  const bandTotal = s ? s.bands.critical + s.bands.high + s.bands.medium + s.bands.low : 0;

  function openGroup(id: string) {
    navigate({ pathname: `/feed/${id}`, search: params.toString() });
  }

  return (
    <section>
      <h1>Feed</h1>

      {/* Summary bar */}
      <div className="cards">
        <div className="card">
          <div className="num" data-testid="total-groups">
            {s?.totalGroups ?? "—"}
          </div>
          <div className="muted">Grouped issues</div>
          <div className="sev-bar" aria-hidden="true">
            {(["critical", "high", "medium", "low"] as const).map((b) => {
              const n = s?.bands[b] ?? 0;
              const pct = bandTotal ? (n / bandTotal) * 100 : 0;
              return <span key={b} className={`seg-${b}`} style={{ width: `${pct}%` }} />;
            })}
          </div>
          <div className="row" style={{ gap: "0.5rem", fontSize: "0.8rem" }}>
            <span className="sev-critical" data-testid="band-critical">{s?.bands.critical ?? 0} critical</span>
            <span className="sev-high" data-testid="band-high">{s?.bands.high ?? 0} high</span>
            <span className="sev-medium" data-testid="band-medium">{s?.bands.medium ?? 0} medium</span>
            <span className="sev-low" data-testid="band-low">{s?.bands.low ?? 0} low</span>
          </div>
        </div>
        <div className="card">
          <div className="num" data-testid="new-7d">{s?.newFindings ?? "—"}</div>
          <div className="muted">New · last 7 days</div>
        </div>
        <div className="card">
          <div className="num" data-testid="solved-7d">{s?.solved ?? "—"}</div>
          <div className="muted">Solved · last 7 days</div>
        </div>
        <div className="card">
          <div className="num" data-testid="autoignored-7d">{s?.autoIgnored ?? "—"}</div>
          <div className="muted">Auto-ignored · last 7 days</div>
        </div>
      </div>

      {/* Filters */}
      <div className="toolbar">
        <div className="toggle-group" role="group" aria-label="View">
          <button className={view === "focus" ? "active" : ""} onClick={() => setFilter("view", "focus")}>
            Focus
          </button>
          <button className={view === "all" ? "active" : ""} onClick={() => setFilter("view", "all")}>
            All
          </button>
        </div>
        <select aria-label="Type" value={type ?? ""} onChange={(e) => setFilter("type", e.target.value || null)}>
          <option value="">All types</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {TYPE_LABEL[t]}
            </option>
          ))}
        </select>
        <select aria-label="Severity" value={band ?? ""} onChange={(e) => setFilter("band", e.target.value || null)}>
          <option value="">All severities</option>
          {BANDS.map((b) => (
            <option key={b} value={b}>
              {titleCase(b)}
            </option>
          ))}
        </select>
        <select
          aria-label="Repository"
          value={repositoryId ?? ""}
          onChange={(e) => setFilter("repositoryId", e.target.value || null)}
        >
          <option value="">All repositories</option>
          {repos.data?.content.map((r) => (
            <option key={r.id} value={r.id}>
              {r.pathWithNamespace}
            </option>
          ))}
        </select>
        <label className="row">
          <input
            type="checkbox"
            checked={includeInactive}
            onChange={(e) => setFilter("includeInactive", e.target.checked ? "true" : null)}
          />
          Include inactive
        </label>
      </div>

      {feed.error ? <ErrorBanner error={feed.error} /> : null}

      <table className="table">
        <thead>
          <tr>
            <th>Type</th>
            <th>Name</th>
            <th>Severity</th>
            <th>Repository</th>
            <th>Fix time</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {feed.data?.content.map((g) => {
            const status =
              g.status === "ignored"
                ? { label: "Ignored", cls: "sev-ignored" }
                : g.status === "open"
                  ? withinSevenDays(g.firstSeenAt)
                    ? { label: "New", cls: "sev-high" }
                    : { label: "To Do", cls: "sev-ignored" }
                  : { label: titleCase(g.status), cls: "sev-ignored" };
            const sevClass =
              g.severityScore == null
                ? "sev-ignored"
                : g.severityScore >= 90
                  ? "sev-critical"
                  : g.severityScore >= 70
                    ? "sev-high"
                    : g.severityScore >= 40
                      ? "sev-medium"
                      : "sev-low";
            return (
              <tr key={g.id} className="row-click" onClick={() => openGroup(g.id)} data-testid="feed-row">
                <td>
                  <span className="badge badge-inactive">{TYPE_LABEL[g.type]}</span>
                </td>
                <td>
                  <div>
                    <strong>{g.title}</strong>
                  </div>
                  {g.subtitle ? <div className="muted">{g.subtitle}</div> : null}
                </td>
                <td>
                  <span className={`badge ${sevClass}`}>
                    {g.severityScore == null ? "Ignored" : g.severityScore}
                  </span>
                </td>
                <td>{g.repositoryPath}</td>
                <td>{fixTime(g.fixTimeMinutes)}</td>
                <td>
                  <span className={`badge ${status.cls}`}>{status.label}</span>
                </td>
              </tr>
            );
          })}
          {feed.data && feed.data.content.length === 0 ? (
            <tr>
              <td colSpan={6} className="muted">
                No issues match these filters.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>

      <div className="pager">
        <button disabled={page === 0} onClick={() => setFilter("page", String(page - 1))}>
          Previous
        </button>
        <span className="muted" data-testid="page-info">
          Page {page + 1} of {feed.data?.totalPages ?? 1} · {feed.data?.totalElements ?? 0} groups
        </span>
        <button
          disabled={page + 1 >= (feed.data?.totalPages ?? 1)}
          onClick={() => setFilter("page", String(page + 1))}
        >
          Next
        </button>
      </div>

      {groupId ? (
        <SubIssuePanel groupId={groupId} onClose={() => navigate({ pathname: "/feed", search: params.toString() })} />
      ) : null}
    </section>
  );
}
