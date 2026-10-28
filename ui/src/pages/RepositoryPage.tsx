import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { useQuery } from "@tanstack/react-query";
import {
  api,
  type IssueGroupView,
  type IssueType,
  type View,
} from "../api";
import { qk } from "../queryClient";
import { isUuid, oneOf } from "../lib/validate";
import { fixTime } from "../lib/format";
import { humanizeRuleId, typeLabel } from "../lib/labels";
import { coverageBanners } from "../lib/coverage";
import RelativeTime from "../components/RelativeTime";
import ScanButton from "../components/ScanButton";
import RowMenu from "../components/RowMenu";
import RepoSettingsForm from "../components/RepoSettingsForm";
import SubIssuePanel from "../components/SubIssuePanel";
import SeverityPill from "../components/SeverityPill";
import StatusChip, { type StatusKind } from "../components/StatusChip";
import TypeBadge from "../components/TypeBadge";
import Icon from "../components/icons";
import WarningsDrawer from "../components/WarningsDrawer";
import ErrorBanner from "../components/ErrorBanner";
import { TableSkeleton } from "../components/Skeleton";
import { PageHeader, EmptyState, ScanStatusChip, PathText } from "../components/ui";

const SIZE = 50;
const TYPES: IssueType[] = ["sca", "sast", "secret", "iac", "eol"];
const TABS = ["issues", "scans", "settings"] as const;
type Tab = (typeof TABS)[number];
const TAB_LABEL: Record<Tab, string> = { issues: "Issues", scans: "Scans", settings: "Settings" };

function withinSevenDays(iso: string): boolean {
  return Date.now() - new Date(iso).getTime() < 7 * 86400 * 1000;
}
function statusKind(g: IssueGroupView): StatusKind {
  if (g.status === "ignored") return "ignored";
  if (g.status === "solved") return "solved";
  return withinSevenDays(g.firstSeenAt) ? "new" : "todo";
}
function titleFor(g: IssueGroupView): { title: string; subtitle: string | null } {
  if (g.type === "sast") return { title: humanizeRuleId(g.title), subtitle: g.title };
  return { title: g.title, subtitle: g.subtitle };
}

/** The repository page (/repositories/:id). M8.6 item 4: header + coverage banner + URL-addressable
 *  Issues / Scans / Settings tabs. */
export default function RepositoryPage() {
  const { id } = useParams();
  const [sp, setSp] = useSearchParams();
  const valid = isUuid(id);

  const repo = useQuery({
    queryKey: qk.repository(id ?? ""),
    queryFn: () => api.getRepository(id as string),
    enabled: valid,
  });

  const tabParam = sp.get("tab");
  const tab: Tab = (TABS as readonly string[]).includes(tabParam ?? "") ? (tabParam as Tab) : "issues";

  function goTab(t: Tab) {
    const next = new URLSearchParams(sp);
    if (t === "issues") next.delete("tab");
    else next.set("tab", t);
    next.delete("issue"); // leaving the Issues tab closes any open panel
    setSp(next, { replace: false });
  }

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
                        goTab("settings");
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

          {r.lastScan ? <CoverageBanner scanId={r.lastScan.id} /> : null}

          <nav className="tabs" role="tablist" aria-label="Repository sections">
            {TABS.map((t) => (
              <button
                key={t}
                role="tab"
                aria-selected={tab === t}
                className={tab === t ? "tab active" : "tab"}
                onClick={() => goTab(t)}
              >
                {TAB_LABEL[t]}
              </button>
            ))}
          </nav>

          {tab === "issues" ? <RepoIssuesTab repoId={r.id} /> : null}
          {tab === "scans" ? <RepoScansTab repoId={r.id} repoPath={r.pathWithNamespace} /> : null}
          {tab === "settings" ? (
            <div className="card settings-card">
              <RepoSettingsForm repo={r} onSaved={() => repo.refetch()} />
            </div>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

/** Coverage banner — built only from stored scan data (latest scan tasks + warning codes). No signal → nothing. */
function CoverageBanner({ scanId }: { scanId: string }) {
  const detail = useQuery({ queryKey: qk.scanDetail(scanId), queryFn: () => api.getScan(scanId) });
  const warns = useQuery({
    queryKey: qk.scanWarnings(scanId, 0),
    queryFn: () => api.listScanWarnings(scanId, 0, 200),
  });
  if (!detail.data || !warns.data) return null;
  const banners = coverageBanners({
    tasks: detail.data.tasks ?? [],
    warningCodes: warns.data.content.map((w) => w.code),
  });
  if (banners.length === 0) return null;
  return (
    <div className="coverage-banner" role="status" data-testid="coverage-banner">
      <Icon name="eol" className="coverage-icon" />
      <ul>
        {banners.map((b, i) => (
          <li key={i}>{b}</li>
        ))}
      </ul>
    </div>
  );
}

/** Issues tab — repo-scoped issue table with search / type / category / Focus-All, server-side pagination, a
 *  row ⋮ (Open / Ignore group…), and the side panel (opened via ?issue, so a reload/share reopens it). */
function RepoIssuesTab({ repoId }: { repoId: string }) {
  const [sp, setSp] = useSearchParams();
  const [type, setType] = useState<IssueType | "">("");
  const [category, setCategory] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);

  const viewCheck = oneOf<View>(sp.get("view"), ["focus", "all"]);
  const view: View = (viewCheck.ok ? viewCheck.value : undefined) ?? "focus";
  const openIssue = sp.get("issue");
  const openIssueOk = !openIssue || isUuid(openIssue);
  const openIgnore = sp.get("ignore") === "1"; // N2: the ⋮ "Ignore…" flag

  const filters = {
    repositoryId: repoId,
    type: type || undefined,
    category: category || undefined,
    search: search.trim() || undefined,
    view,
    page,
    size: SIZE,
  };

  const feed = useQuery({ queryKey: qk.feed(filters), queryFn: () => api.listFeed(filters) });
  const cats = useQuery({
    queryKey: qk.issueCategories({ repoId, view }),
    queryFn: () => api.listIssueCategories(repoId, view),
  });

  function reset(fn: () => void) {
    setPage(0);
    fn();
  }
  function setView(v: View) {
    const next = new URLSearchParams(sp);
    next.set("view", v);
    setSp(next, { replace: false });
    setPage(0);
  }
  function openPanel(groupId: string) {
    const next = new URLSearchParams(sp);
    next.set("issue", groupId);
    next.delete("ignore"); // a plain Open (or next/prev) never carries the ignore-dialog flag
    setSp(next, { replace: false });
  }
  // N2: ⋮ "Ignore…" opens the panel with the group-ignore dialog already open (distinct from "Open").
  function openPanelIgnore(groupId: string) {
    const next = new URLSearchParams(sp);
    next.set("issue", groupId);
    next.set("ignore", "1");
    setSp(next, { replace: false });
  }
  function closePanel() {
    const next = new URLSearchParams(sp);
    next.delete("issue");
    next.delete("ignore");
    setSp(next, { replace: false });
  }

  const rows = feed.data?.content ?? [];

  return (
    <div>
      <div className="toolbar">
        <div className="segmented" role="group" aria-label="View">
          <button className={view === "focus" ? "active" : ""} onClick={() => setView("focus")}>
            Focus
          </button>
          <button className={view === "all" ? "active" : ""} onClick={() => setView("all")}>
            All
          </button>
        </div>
        <div className="search-field">
          <Icon name="search" className="search-icon" />
          <input
            type="search"
            aria-label="Search issues"
            placeholder="Search title"
            value={search}
            onChange={(e) => reset(() => setSearch(e.target.value))}
          />
        </div>
        <select aria-label="Type" value={type} onChange={(e) => reset(() => setType(e.target.value as IssueType | ""))}>
          <option value="">All types</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>
              {typeLabel(t)}
            </option>
          ))}
        </select>
        <select aria-label="Category" value={category} onChange={(e) => reset(() => setCategory(e.target.value))}>
          <option value="">All categories</option>
          {(cats.data ?? []).map((c) => (
            <option key={c.category} value={c.category}>
              {c.category} ({c.count})
            </option>
          ))}
        </select>
      </div>

      {feed.error ? <ErrorBanner error={feed.error} /> : null}

      {rows.length === 0 && !feed.isLoading ? (
        <EmptyState title="No issues match these filters">
          Switch to All, clear a filter, or change the search.
        </EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Name</th>
                <th>Severity</th>
                <th>Location</th>
                <th>Fix time</th>
                <th>Status</th>
                <th>
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            {feed.isLoading ? (
              <TableSkeleton columns={7} />
            ) : (
              <tbody>
                {rows.map((g) => {
                  const t = titleFor(g);
                  const more = g.locationCount > 1 ? g.locationCount - 1 : 0;
                  return (
                    <tr
                      key={g.id}
                      className="row-click"
                      tabIndex={0}
                      data-testid="repo-issue-row"
                      onClick={() => openPanel(g.id)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" || e.key === " ") {
                          e.preventDefault();
                          openPanel(g.id);
                        }
                      }}
                    >
                      <td>
                        <div className="type-cell">
                          <TypeBadge type={g.type} />
                          {g.level2Category ? <span className="muted category-tag">{g.level2Category}</span> : null}
                        </div>
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
                      <td>
                        <SeverityPill score={g.severityScore} />
                      </td>
                      <td>
                        {g.primaryLocation ? (
                          <span className="location-cell">
                            <PathText path={g.primaryLocation} />
                            {more > 0 ? <span className="muted"> +{more} more</span> : null}
                          </span>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td>{fixTime(g.fixTimeMinutes)}</td>
                      <td>
                        <StatusChip kind={statusKind(g)} />
                      </td>
                      <td className="cell-actions">
                        <RowMenu>
                          {(close) => (
                            <>
                              <button
                                type="button"
                                className="rowmenu-item"
                                onClick={() => {
                                  close();
                                  openPanel(g.id);
                                }}
                              >
                                Open
                              </button>
                              <button
                                type="button"
                                className="rowmenu-item"
                                onClick={() => {
                                  close();
                                  openPanelIgnore(g.id);
                                }}
                              >
                                Ignore…
                              </button>
                            </>
                          )}
                        </RowMenu>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            )}
          </table>
        </div>
      )}

      <div className="pager">
        <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <span data-testid="repo-issues-page-info">
          Page {page + 1} of {feed.data?.totalPages ?? 1} · {feed.data?.totalElements ?? 0} issues
        </span>
        <button
          className="btn btn-secondary btn-sm"
          disabled={page + 1 >= (feed.data?.totalPages ?? 1)}
          onClick={() => setPage((p) => p + 1)}
        >
          Next
        </button>
      </div>

      {openIssue && openIssueOk ? (
        <SubIssuePanel
          groupId={openIssue}
          siblingIds={rows.map((g) => g.id)}
          onNavigate={openPanel}
          onClose={closePanel}
          initialIgnoreOpen={openIgnore}
        />
      ) : null}
    </div>
  );
}

/** Scans tab — the repository's scans with status, branch, short commit, duration and a warnings drawer. */
function RepoScansTab({ repoId, repoPath }: { repoId: string; repoPath: string }) {
  const [page, setPage] = useState(0);
  const [warnings, setWarnings] = useState<string | null>(null);
  const scans = useQuery({
    queryKey: qk.repositoryScans(repoId, page),
    queryFn: () => api.listRepositoryScans(repoId, page, SIZE),
  });
  const rows = scans.data?.content ?? [];

  return (
    <div>
      {scans.error ? <ErrorBanner error={scans.error} /> : null}
      {rows.length === 0 && !scans.isLoading ? (
        <EmptyState title="No scans yet">This repository has not been scanned.</EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Status</th>
                <th>Branch</th>
                <th>Commit</th>
                <th>Started</th>
                <th>Duration</th>
                <th>Trigger</th>
                <th>Warnings</th>
              </tr>
            </thead>
            {scans.isLoading ? (
              <TableSkeleton columns={7} />
            ) : (
              <tbody>
                {rows.map((s) => (
                  <tr key={s.id} data-testid="scan-row">
                    <td>
                      <ScanStatusChip status={s.status} />
                    </td>
                    <td className="branch-label">{s.branch ?? "—"}</td>
                    <td className="mono">{s.commitSha ? s.commitSha.slice(0, 8) : "—"}</td>
                    <td>
                      <RelativeTime iso={s.startedAt ?? s.createdAt} />
                    </td>
                    <td>{scanDuration(s.startedAt, s.finishedAt)}</td>
                    <td>{s.trigger}</td>
                    <td>
                      {s.warningCount > 0 ? (
                        <button className="btn btn-ghost btn-sm chip-warn-link" onClick={() => setWarnings(s.id)}>
                          {s.warningCount} warning{s.warningCount === 1 ? "" : "s"}
                        </button>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            )}
          </table>
        </div>
      )}
      <div className="pager">
        <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <span>
          Page {page + 1} of {scans.data?.totalPages ?? 1}
        </span>
        <button
          className="btn btn-secondary btn-sm"
          disabled={page + 1 >= (scans.data?.totalPages ?? 1)}
          onClick={() => setPage((p) => p + 1)}
        >
          Next
        </button>
      </div>
      {warnings ? (
        <WarningsDrawer scanId={warnings} repositoryPath={repoPath} onClose={() => setWarnings(null)} />
      ) : null}
    </div>
  );
}

function scanDuration(startedAt: string | null, finishedAt: string | null): string {
  if (!startedAt || !finishedAt) return "—";
  const ms = new Date(finishedAt).getTime() - new Date(startedAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return "—";
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  return `${m}m ${s % 60}s`;
}
