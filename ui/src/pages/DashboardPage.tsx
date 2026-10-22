import { useQuery } from "@tanstack/react-query";
import { api } from "../api";
import { qk } from "../queryClient";
import { titleCase } from "../lib/format";
import RelativeTime from "../components/RelativeTime";
import ErrorBanner from "../components/ErrorBanner";

const TYPE_LABEL: Record<string, string> = { sca: "SCA", sast: "SAST", secret: "Secret", iac: "IaC", eol: "EOL" };

export default function DashboardPage() {
  const dash = useQuery({ queryKey: qk.dashboard(false), queryFn: () => api.dashboardSummary(false) });
  const scans = useQuery({ queryKey: ["scans-recent"], queryFn: () => api.listScansAll(0, 10) });
  const repos = useQuery({ queryKey: qk.repositories(0, 100), queryFn: () => api.listRepositories(0, 100) });

  const d = dash.data;
  const repoPath = new Map((repos.data?.content ?? []).map((r) => [r.id, r.pathWithNamespace]));
  const maxType = Math.max(1, ...(d?.byType ?? []).map((t) => t.count));

  return (
    <section>
      <h1>Dashboard</h1>
      {dash.error ? <ErrorBanner error={dash.error} /> : null}

      <div className="cards">
        <BandCard title="Issue groups" total={d?.totalGroups} bands={d?.groupBands} testid="group" />
        <BandCard title="Findings" total={d?.totalFindings} bands={d?.findingBands} testid="finding" />
      </div>

      <h2>Issue types</h2>
      <div className="hbars" data-testid="type-breakdown">
        {(d?.byType ?? []).length === 0 ? <p className="muted">No open findings.</p> : null}
        {(d?.byType ?? []).map((t) => (
          <div key={t.type} className="row" data-testid={`type-${t.type}`}>
            <span style={{ width: 70 }}>{TYPE_LABEL[t.type] ?? t.type}</span>
            <div className="hbar-track" style={{ flex: 1 }}>
              <div className="hbar-fill" style={{ width: `${(t.count / maxType) * 100}%` }} />
            </div>
            <span style={{ width: 40, textAlign: "right" }}>{t.count}</span>
          </div>
        ))}
      </div>

      <h2 style={{ marginTop: "1.5rem" }}>Scan health</h2>
      <p className="muted" data-testid="scan-health-count">
        {d?.scanHealth.unhealthyRepoCount ?? 0} repositor
        {(d?.scanHealth.unhealthyRepoCount ?? 0) === 1 ? "y" : "ies"} with a partial/failed scan or warnings
      </p>
      {d && d.scanHealth.repos.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Repository</th>
              <th>Last scan</th>
              <th>Warnings</th>
            </tr>
          </thead>
          <tbody>
            {d.scanHealth.repos.map((r) => (
              <tr key={r.repositoryPath} data-testid="health-row">
                <td>{r.repositoryPath}</td>
                <td>
                  <span className={`badge ${r.lastScanStatus === "failed" ? "sev-critical" : "sev-high"}`}>
                    {titleCase(r.lastScanStatus)}
                  </span>
                </td>
                <td>{r.warningCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {d && d.inactive.inactiveRepoCount > 0 ? (
        <p className="muted" data-testid="inactive-line">
          {d.inactive.inactiveRepoCount} inactive repo{d.inactive.inactiveRepoCount === 1 ? "" : "s"},{" "}
          {d.inactive.openFindingsInInactiveRepos} open finding
          {d.inactive.openFindingsInInactiveRepos === 1 ? "" : "s"} (not scanned)
        </p>
      ) : null}

      <h2 style={{ marginTop: "1.5rem" }}>Recent scans</h2>
      <table className="table">
        <thead>
          <tr>
            <th>Repository</th>
            <th>Trigger</th>
            <th>Status</th>
            <th>Finished</th>
          </tr>
        </thead>
        <tbody>
          {scans.data?.content.map((s) => (
            <tr key={s.id} data-testid="scan-row">
              <td>{repoPath.get(s.repositoryId) ?? s.repositoryId}</td>
              <td>{s.trigger}</td>
              <td>{titleCase(s.status)}</td>
              <td>
                <RelativeTime iso={s.finishedAt ?? s.createdAt} />
              </td>
            </tr>
          ))}
          {scans.data && scans.data.content.length === 0 ? (
            <tr>
              <td colSpan={4} className="muted">
                No scans yet.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
    </section>
  );
}

function BandCard({
  title,
  total,
  bands,
  testid,
}: {
  title: string;
  total: number | undefined;
  bands: { critical: number; high: number; medium: number; low: number } | undefined;
  testid: string;
}) {
  const b = bands ?? { critical: 0, high: 0, medium: 0, low: 0 };
  const sum = b.critical + b.high + b.medium + b.low || 1;
  return (
    <div className="card">
      <div className="num" data-testid={`${testid}-total`}>
        {total ?? "—"}
      </div>
      <div className="muted">{title}</div>
      <div className="sev-bar" aria-hidden="true">
        {(["critical", "high", "medium", "low"] as const).map((k) => (
          <span key={k} className={`seg-${k}`} style={{ width: `${(b[k] / sum) * 100}%` }} />
        ))}
      </div>
      <div className="row" style={{ gap: "0.5rem", fontSize: "0.8rem" }}>
        <span className="sev-critical" data-testid={`${testid}-critical`}>{b.critical} crit</span>
        <span className="sev-high" data-testid={`${testid}-high`}>{b.high} high</span>
        <span className="sev-medium" data-testid={`${testid}-medium`}>{b.medium} med</span>
        <span className="sev-low" data-testid={`${testid}-low`}>{b.low} low</span>
      </div>
    </div>
  );
}
