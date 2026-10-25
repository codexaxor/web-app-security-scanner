import { useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import { titleCase } from "../lib/format";
import { typeAcronym, typeLabel } from "../lib/labels";
import RelativeTime from "../components/RelativeTime";
import RepoName from "../components/RepoName";
import WarningsDrawer from "../components/WarningsDrawer";
import ErrorBanner from "../components/ErrorBanner";
import { Card, PageHeader, EmptyState, SeverityBar, Legend, ScanStatusChip } from "../components/ui";
import type { BandName } from "../lib/format";

type Bands = { critical: number; high: number; medium: number; low: number };

function duration(startedAt: string | null, finishedAt: string | null): string {
  if (!startedAt || !finishedAt) return "—";
  const secs = Math.max(0, Math.round((new Date(finishedAt).getTime() - new Date(startedAt).getTime()) / 1000));
  if (secs < 60) return `${secs}s`;
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return s ? `${m}m ${s}s` : `${m}m`;
}

export default function DashboardPage() {
  const dash = useQuery({ queryKey: qk.dashboard(false), queryFn: () => api.dashboardSummary(false) });
  const scans = useQuery({ queryKey: qk.scansRecent, queryFn: () => api.listScansAll(0, 10) });
  const repos = useQuery({ queryKey: qk.repositories(0, 100), queryFn: () => api.listRepositories(0, 100) });
  const [warnings, setWarnings] = useState<{ scanId: string; path: string } | null>(null);

  const d = dash.data;
  const repoByPath = new Map((repos.data?.content ?? []).map((r) => [r.pathWithNamespace, r]));
  const repoById = new Map((repos.data?.content ?? []).map((r) => [r.id, r.pathWithNamespace]));
  const maxType = Math.max(1, ...(d?.byType ?? []).map((t) => t.count));

  const healthRepos = d?.scanHealth.repos ?? [];
  const failedPartial = healthRepos.filter((r) => r.lastScanStatus === "failed" || r.lastScanStatus === "partial");
  const withWarnings = healthRepos.filter((r) => r.lastScanStatus !== "failed" && r.lastScanStatus !== "partial");
  const bandLink = (b: BandName) => `/feed?band=${b}`;

  return (
    <section>
      <PageHeader title="Dashboard" subtitle="Posture across your connected repositories." />
      {dash.error ? <ErrorBanner error={dash.error} /> : null}

      <div className="cards">
        <BandCard title="Issue groups" total={d?.totalGroups} bands={d?.groupBands} prefix="group" link={bandLink} />
        <BandCard title="Findings" total={d?.totalFindings} bands={d?.findingBands} prefix="finding" link={bandLink} />
      </div>

      <h2 className="section-head">Issue types</h2>
      <div className="hbars" data-testid="type-breakdown">
        {(d?.byType ?? []).length === 0 ? <p className="muted">No open findings.</p> : null}
        {(d?.byType ?? []).map((t) => (
          <Link key={t.type} className="hbar" to={`/feed?type=${t.type}`} data-testid={`type-${t.type}`}>
            <span className="hbar-label" title={typeAcronym(t.type)}>
              {typeLabel(t.type)}
            </span>
            <span className="hbar-track">
              <span className="hbar-fill" style={{ width: `${(t.count / maxType) * 100}%` }} />
            </span>
            <span className="hbar-val">{t.count}</span>
          </Link>
        ))}
      </div>

      <h2 className="section-head">Scan health</h2>
      {healthRepos.length === 0 ? (
        <p className="muted" data-testid="scan-health-count">All scans healthy</p>
      ) : (
        <p className="muted" data-testid="scan-health-count">
          {d?.scanHealth.unhealthyRepoCount ?? 0}{" "}
          {(d?.scanHealth.unhealthyRepoCount ?? 0) === 1 ? "repository needs" : "repositories need"} attention —{" "}
          {(d?.scanHealth.failedPartialCount ?? 0) > 0
            ? `${d?.scanHealth.failedPartialCount} failed/partial`
            : "all scans completed"}{" "}
          · {d?.scanHealth.withWarningsCount ?? 0} with warning
          {(d?.scanHealth.withWarningsCount ?? 0) === 1 ? "" : "s"}
        </p>
      )}
      {healthRepos.length === 0 ? null : (
        <div className="stack-4">
          {failedPartial.length > 0 ? (
            <div>
              <div className="health-label health-bad">Failed / partial</div>
              <div className="chip-row">
                {failedPartial.map((r) => (
                  <span key={r.repositoryPath} className="chip chip-ignored health-item" data-testid="health-row">
                    <RepoName path={r.repositoryPath} />
                    {" · "}
                    {titleCase(r.lastScanStatus)}
                  </span>
                ))}
              </div>
            </div>
          ) : null}
          {withWarnings.length > 0 ? (
            <div>
              <div className="health-label health-warn">Completed with warnings</div>
              <div className="chip-row">
                {withWarnings.map((r) => {
                  const repo = repoByPath.get(r.repositoryPath);
                  return (
                    <button
                      key={r.repositoryPath}
                      className="chip chip-warn health-item"
                      data-testid="health-row"
                      disabled={!repo?.lastScan}
                      onClick={() => repo?.lastScan && setWarnings({ scanId: repo.lastScan.id, path: r.repositoryPath })}
                    >
                      <RepoName path={r.repositoryPath} />
                      {" · "}
                      {r.warningCount} warning{r.warningCount === 1 ? "" : "s"}
                    </button>
                  );
                })}
              </div>
            </div>
          ) : null}
        </div>
      )}

      {d && d.inactive.inactiveRepoCount > 0 ? (
        <p className="muted inactive-note" data-testid="inactive-line">
          {d.inactive.inactiveRepoCount} inactive repo{d.inactive.inactiveRepoCount === 1 ? "" : "s"},{" "}
          {d.inactive.openFindingsInInactiveRepos} open finding
          {d.inactive.openFindingsInInactiveRepos === 1 ? "" : "s"} (not scanned)
        </p>
      ) : null}

      <h2 className="section-head">Recent scans</h2>
      {scans.data && scans.data.content.length === 0 ? (
        <EmptyState title="No scans yet">Connect a repository and start a scan.</EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Repository</th>
                <th>Branch</th>
                <th>Trigger</th>
                <th>Status</th>
                <th>Duration</th>
                <th>Finished</th>
              </tr>
            </thead>
            <tbody>
              {scans.data?.content.map((s) => (
                <tr key={s.id} data-testid="scan-row">
                  <td>
                    <RepoName path={repoById.get(s.repositoryId) ?? s.repositoryId} />
                  </td>
                  <td>{s.branch ?? <span className="muted">—</span>}</td>
                  <td>{s.trigger}</td>
                  <td>
                    <ScanStatusChip status={s.status} />
                  </td>
                  <td>{duration(s.startedAt, s.finishedAt)}</td>
                  <td>
                    <RelativeTime iso={s.finishedAt ?? s.createdAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {warnings ? (
        <WarningsDrawer scanId={warnings.scanId} repositoryPath={warnings.path} onClose={() => setWarnings(null)} />
      ) : null}
    </section>
  );
}

function BandCard({
  title,
  total,
  bands,
  prefix,
  link,
}: {
  title: string;
  total: number | undefined;
  bands: Bands | undefined;
  prefix: string;
  link: (b: BandName) => string;
}) {
  const b = bands ?? { critical: 0, high: 0, medium: 0, low: 0 };
  return (
    <Card>
      <div className="card-head">
        <span className="stat-label">{title}</span>
      </div>
      <div className="stat-num" data-testid={`${prefix}-total`}>
        {total ?? "—"}
      </div>
      <SeverityBar bands={b} />
      <Legend bands={b} prefix={prefix} linkTo={link} />
    </Card>
  );
}
