import { useSearchParams, useNavigate, useParams } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api, type FeedParams, type IssueType, type Band, type View, type IssueGroupView } from "../api";
import { qk } from "../queryClient";
import { fixTime, titleCase } from "../lib/format";
import { humanizeRuleId, typeLabel } from "../lib/labels";
import ErrorBanner from "../components/ErrorBanner";
import SubIssuePanel from "../components/SubIssuePanel";
import SeverityPill from "../components/SeverityPill";
import StatusChip, { type StatusKind } from "../components/StatusChip";
import TypeBadge from "../components/TypeBadge";
import RepoName from "../components/RepoName";
import { Card, PageHeader, EmptyState, InfoTip, SeverityBar, Legend } from "../components/ui";

const SIZE = 50;
const TYPES: IssueType[] = ["sca", "sast", "secret", "iac", "eol"];
const BANDS: Band[] = ["critical", "high", "medium", "low"];

function withinSevenDays(iso: string): boolean {
  return Date.now() - new Date(iso).getTime() < 7 * 86400 * 1000;
}

/** The status chip kind + whatever existing text the Feed has always shown ("New"/"To Do"/"Ignored"). */
function statusKind(g: IssueGroupView): StatusKind {
  if (g.status === "ignored") return "ignored";
  if (g.status === "solved") return "solved";
  return withinSevenDays(g.firstSeenAt) ? "new" : "todo";
}

/** Display title + subtitle. SAST titles are humanized from the rule id (existing data only); the raw rule id
 *  becomes the muted subtitle. Other types keep the server title/subtitle. */
function titleFor(g: IssueGroupView): { title: string; subtitle: string | null } {
  if (g.type === "sast") {
    return { title: humanizeRuleId(g.title), subtitle: g.title };
  }
  return { title: g.title, subtitle: g.subtitle };
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
  const bands = s?.bands ?? { critical: 0, high: 0, medium: 0, low: 0 };

  function openGroup(id: string) {
    navigate({ pathname: `/feed/${id}`, search: params.toString() });
  }

  const rows = feed.data?.content ?? [];

  return (
    <section>
      <PageHeader title="Feed" subtitle="One deduplicated, prioritized issue feed across your repositories." />

      <div className="cards">
        <Card>
          <div className="card-head">
            <span className="stat-label">
              Open issue groups
              <InfoTip text="Distinct issue groups users triage, in the current view and filters. Solved groups are never counted." />
            </span>
          </div>
          <div className="stat-num" data-testid="total-groups">
            {s?.totalGroups ?? "—"}
          </div>
          <SeverityBar bands={bands} />
          <Legend bands={bands} prefix="band" />
        </Card>
        <Card>
          <div className="card-head">
            <span className="stat-label">
              Findings first seen · 7d
              <InfoTip text="Findings first detected in the last 7 days (counted even if now ignored or solved)." />
            </span>
          </div>
          <div className="stat-num" data-testid="new-7d">
            {s?.newFindings ?? "—"}
          </div>
        </Card>
        <Card>
          <div className="card-head">
            <span className="stat-label">
              Solved · 7d
              <InfoTip text="Findings moved to solved in the last 7 days (missing from a scan whose scanner succeeded)." />
            </span>
          </div>
          <div className="stat-num" data-testid="solved-7d">
            {s?.solved ?? "—"}
          </div>
        </Card>
        <Card>
          <div className="card-head">
            <span className="stat-label">
              Auto-ignored · 7d
              <InfoTip text="Findings auto-ignored by the system in the last 7 days (dev/test scope, excluded path, placeholder secret, low EPSS, duplicate rule)." />
            </span>
          </div>
          <div className="stat-num" data-testid="autoignored-7d">
            {s?.autoIgnored ?? "—"}
          </div>
        </Card>
      </div>

      <div className="toolbar">
        <div className="segmented" role="group" aria-label="View">
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
              {typeLabel(t)}
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
        <label className="check">
          <input
            type="checkbox"
            checked={includeInactive}
            onChange={(e) => setFilter("includeInactive", e.target.checked ? "true" : null)}
          />
          Include inactive
        </label>
      </div>

      {feed.error ? <ErrorBanner error={feed.error} /> : null}

      {rows.length === 0 && !feed.isLoading ? (
        <EmptyState title="No issues match these filters">
          Try switching to All, clearing a filter, or including inactive repositories.
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Name</th>
                <th className="cell-num">Findings</th>
                <th>Severity</th>
                <th>Repository</th>
                <th>Fix time</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((g) => {
                const t = titleFor(g);
                return (
                  <tr
                    key={g.id}
                    className="row-click"
                    tabIndex={0}
                    onClick={() => openGroup(g.id)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        openGroup(g.id);
                      }
                    }}
                    data-testid="feed-row"
                  >
                    <td>
                      <TypeBadge type={g.type} />
                    </td>
                    <td>
                      <div className="stack-tight">
                        <strong>{t.title}</strong>
                        {t.subtitle ? (
                          <div className={g.type === "sast" ? "muted mono-sub" : "muted subtitle-text"}>
                            {t.subtitle}
                          </div>
                        ) : null}
                      </div>
                    </td>
                    <td className="cell-num" data-testid="findings-count" data-open={g.openCount}>
                      {view === "all" ? (
                        <span>
                          {g.openCount} open
                          {g.ignoredCount > 0 ? ` · ${g.ignoredCount} ignored` : ""}
                        </span>
                      ) : (
                        g.openCount
                      )}
                    </td>
                    <td>
                      <SeverityPill score={g.severityScore} />
                    </td>
                    <td>
                      <RepoName path={g.repositoryPath} />
                    </td>
                    <td>{fixTime(g.fixTimeMinutes)}</td>
                    <td>
                      <StatusChip kind={statusKind(g)} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <div className="pager">
        <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setFilter("page", String(page - 1))}>
          Previous
        </button>
        <span data-testid="page-info">
          Page {page + 1} of {feed.data?.totalPages ?? 1} · {feed.data?.totalElements ?? 0} groups
        </span>
        <button
          className="btn btn-secondary btn-sm"
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
