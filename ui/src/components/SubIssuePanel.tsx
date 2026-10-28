import { useState, type CSSProperties } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type SubIssueView, type TriageEventView } from "../api";
import { invalidateFor, qk } from "../queryClient";
import { advisoryLink, bandOf, titleCase, type BandName } from "../lib/format";
import { humanizeRuleId, reasonLabel, typeLabel } from "../lib/labels";
import { issueFix, issueSummary } from "../lib/issueText";
import { secretFixGuidance } from "../lib/secretGuidance";
import { sourceCommitLink, sourceFileLink } from "../lib/sourceLink";
import RelativeTime from "./RelativeTime";
import ErrorBanner from "./ErrorBanner";
import IgnoreDialog from "./IgnoreDialog";
import ConfirmDialog from "./ConfirmDialog";
import Dialog from "./Dialog";
import Button from "./Button";
import SeverityPill from "./SeverityPill";
import StatusChip, { type StatusKind } from "./StatusChip";
import TypeBadge from "./TypeBadge";
import RepoName from "./RepoName";
import Icon from "./icons";
import { PathText } from "./ui";

const BAND_LABEL: Record<BandName, string> = {
  critical: "Critical",
  high: "High",
  medium: "Medium",
  low: "Low",
};

function withinSevenDays(iso: string | null | undefined): boolean {
  return iso != null && Date.now() - new Date(iso).getTime() < 7 * 86400 * 1000;
}

/** A band-colored severity gauge: a ring whose arc is the score/100, the number in the middle. The arc fraction
 *  is data, so it is an inline CSS variable (CSP-safe via CSSOM, like SeverityBar). Null score → a neutral dash. */
function ScoreGauge({ score }: { score: number | null | undefined }) {
  const band = bandOf(score);
  if (band === "ignored" || score == null) {
    return (
      <div className="gauge gauge-none" role="img" aria-label="No severity (ignored)">
        <span className="gauge-score">—</span>
      </div>
    );
  }
  return (
    <div
      className={`gauge gauge-${band}`}
      style={{ "--pct": String(Math.max(0, Math.min(100, score))) } as CSSProperties}
      role="img"
      aria-label={`Severity ${score} out of 100 (${BAND_LABEL[band]})`}
    >
      <span className="gauge-score">{score}</span>
      <span className="gauge-band">{BAND_LABEL[band]}</span>
    </div>
  );
}

/**
 * The issue side panel (M8.6 item 5). Header: severity gauge, title, subtitle, type + category chip, status,
 * first seen, and prev/next through the current list. Body: a deterministic "TL;DR" and "How do I fix it?"
 * (from stored fields only, no AI — {@link issueSummary}/{@link issueFix}), the stored advisory text, the
 * sub-issues grouped by location (each keeping its CVE/KEV/EPSS/version/line/ignore/history detail), and a
 * "Why this score" breakdown per finding. Esc / backdrop close via {@link Dialog}.
 */
export default function SubIssuePanel({
  groupId,
  onClose,
  siblingIds,
  onNavigate,
  initialIgnoreOpen = false,
}: {
  groupId: string;
  onClose: () => void;
  siblingIds?: string[];
  onNavigate?: (groupId: string) => void;
  /** N2: open the panel with the group-ignore dialog already showing (the Issues-tab ⋮ "Ignore…" action). */
  initialIgnoreOpen?: boolean;
}) {
  const group = useQuery({ queryKey: qk.group(groupId), queryFn: () => api.getGroup(groupId) });
  const q = useQuery({
    queryKey: qk.subIssues(groupId, 0),
    queryFn: () => api.listSubIssues(groupId, 0, 100),
  });
  const [ignoreGroup, setIgnoreGroup] = useState(initialIgnoreOpen);

  const subs = q.data?.content ?? [];
  const g = group.data;
  const title = g ? (g.type === "sast" ? humanizeRuleId(g.title) : g.title) : "Issue";
  // N-3: the affected-locations count is the server total, not subs.length (which caps at the 100-row page).
  const affected = q.data?.totalElements ?? subs.length;

  const summary = g ? issueSummary(g, subs) : null;
  // For secrets the fix guidance is category-specific (D-084 item 6); other types use the generic generator.
  const fix = g
    ? g.type === "secret"
      ? secretFixGuidance(subs[0]?.level2Category ?? null)
      : issueFix(g, subs)
    : null;
  // Stored advisory text (sub_issue.advisory_summary). Hidden when it merely repeats the title (e.g. IaC, where
  // it carries the Checkov check_name that is already the title).
  const advisory = subs.find((s) => s.advisorySummary)?.advisorySummary ?? null;
  const showAdvisory = advisory && advisory !== g?.title;

  const statusKind: StatusKind = g
    ? g.status === "ignored"
      ? "ignored"
      : g.status === "solved"
        ? "solved"
        : withinSevenDays(g.firstSeenAt)
          ? "new"
          : "todo"
    : "todo";

  const idx = siblingIds ? siblingIds.indexOf(groupId) : -1;
  const prevId = idx > 0 ? siblingIds![idx - 1] : null;
  const nextId = idx >= 0 && siblingIds && idx < siblingIds.length - 1 ? siblingIds[idx + 1] : null;

  const byLocation = groupByLocation(subs);

  return (
    <Dialog title="Issue details" onClose={onClose} variant="drawer">
      <div className="panel-header">
        <div className="row-between">
          <div className="row">
            {g ? <TypeBadge type={g.type} /> : null}
            {g?.level2Category ? <span className="chip chip-todo category-chip">{g.level2Category}</span> : null}
            {g ? <StatusChip kind={statusKind} /> : null}
          </div>
          <div className="row panel-nav">
            {onNavigate && siblingIds ? (
              <>
                <button
                  className="icon-btn"
                  aria-label="Previous issue"
                  disabled={!prevId}
                  onClick={() => prevId && onNavigate(prevId)}
                >
                  <Icon name="chevronLeft" />
                </button>
                <button
                  className="icon-btn"
                  aria-label="Next issue"
                  disabled={!nextId}
                  onClick={() => nextId && onNavigate(nextId)}
                >
                  <Icon name="chevronRight" />
                </button>
              </>
            ) : null}
            <button className="panel-close" onClick={onClose} aria-label="Close">
              ×
            </button>
          </div>
        </div>
        <div className="panel-title-row">
          <ScoreGauge score={g?.severityScore} />
          <div>
            <h2 className="panel-title">{title}</h2>
            {g ? (
              <div className="muted panel-sub">
                <RepoName path={g.repositoryPath} showPath />
                {g.repositoryBranch ? ` · ${g.repositoryBranch}` : ""} · {typeLabel(g.type)}
              </div>
            ) : null}
            {g ? (
              <div className="muted panel-firstseen">
                First seen <RelativeTime iso={g.firstSeenAt} />
              </div>
            ) : null}
          </div>
        </div>
      </div>

      <div className="panel-body">
        {group.error ? <ErrorBanner error={group.error} /> : null}
        {q.error ? <ErrorBanner error={q.error} /> : null}

        {summary ? (
          <section className="panel-section">
            <h3 className="panel-h3">TL;DR</h3>
            <p className="panel-text" data-testid="tldr">
              {summary}
            </p>
          </section>
        ) : null}

        {fix ? (
          <section className="panel-section">
            <h3 className="panel-h3">How do I fix it?</h3>
            <p className="panel-text" data-testid="howtofix">
              {fix}
            </p>
          </section>
        ) : null}

        {showAdvisory ? (
          <section className="panel-section">
            <h3 className="panel-h3">Advisory</h3>
            <p className="panel-text subtitle-text" data-testid="advisory">
              {advisory}
            </p>
          </section>
        ) : null}

        {g ? (
          <div className="group-head">
            <div className="row-between">
              <span className="muted" data-testid="affected-count">
                {affected} affected location{affected === 1 ? "" : "s"}
              </span>
              {subs.length > 0 ? (
                <Button variant="secondary" size="sm" onClick={() => setIgnoreGroup(true)}>
                  Ignore group…
                </Button>
              ) : null}
            </div>
          </div>
        ) : null}

        {q.isLoading ? <p className="muted">Loading…</p> : null}

        {byLocation.map(([file, locSubs]) => (
          <div className="loc-group" key={file}>
            <div className="loc-head">
              <PathText path={file} />
              <span className="muted"> · {locSubs.length}</span>
            </div>
            {locSubs.map((s) => (
              <LocationCard key={s.id} sub={s} groupId={groupId} projectPath={g?.repositoryPath ?? null} />
            ))}
          </div>
        ))}

        {ignoreGroup && subs[0] ? (
          <IgnoreDialog
            sub={subs[0]}
            groupId={groupId}
            defaultScope="group"
            onClose={() => setIgnoreGroup(false)}
          />
        ) : null}
      </div>
    </Dialog>
  );
}

/** Group a group's sub-issues by their file path (findings with no path fall under "No file"), preserving the
 *  server order of first appearance. */
function groupByLocation(subs: SubIssueView[]): [string, SubIssueView[]][] {
  const map = new Map<string, SubIssueView[]>();
  for (const s of subs) {
    const key = s.filePath ?? "No file";
    const list = map.get(key);
    if (list) list.push(s);
    else map.set(key, [s]);
  }
  return Array.from(map.entries());
}

function LocationCard({
  sub,
  groupId,
  projectPath,
}: {
  sub: SubIssueView;
  groupId: string;
  projectPath: string | null;
}) {
  const [showIgnore, setShowIgnore] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showBreakdown, setShowBreakdown] = useState(false);
  const [confirmUnignore, setConfirmUnignore] = useState(false);

  const unignore = useMutation({
    mutationFn: () => api.deleteIgnoreRule(sub.ignoreRuleId as string),
    onSuccess: () => {
      setConfirmUnignore(false);
      return invalidateFor("triage");
    },
  });

  const link = advisoryLink(sub.vulnId);
  const isSecscanYml = sub.ruleCreatedBy === "secscan.yml";
  const heldByRule = sub.ignoreRuleId != null;
  const idLabel = sub.vulnId ?? sub.ruleId ?? sub.packageName ?? sub.type;

  // D-084: file + commit deep links, built only from the stored host/project/commit/path/line (never data).
  const ref = { host: sub.host, projectPath, commitSha: sub.commitSha };
  const fileLink = sub.filePath ? sourceFileLink(ref, sub.filePath, sub.line) : null;
  const commitLink = sourceCommitLink(ref);

  return (
    <div className="location" data-testid="subissue">
      <div className="location-head">
        <div className="location-id-wrap">
          <span className="location-id">
            {link ? (
              <a href={link} target="_blank" rel="noreferrer">
                {sub.vulnId}
              </a>
            ) : (
              idLabel
            )}
          </span>
          {sub.line != null ? <span className="muted"> line {sub.line}</span> : null}
        </div>
        <span className="location-badges">
          {sub.inKev ? (
            <span className="badge-kev" data-testid="kev-badge">
              KEV
            </span>
          ) : null}
          {sub.score != null ? <SeverityPill score={sub.score} showScore /> : null}
        </span>
      </div>

      <dl className="kv">
        {sub.installedVersion || sub.fixedVersion ? (
          <>
            <dt>Version</dt>
            <dd>
              {sub.installedVersion ?? "?"} → {sub.fixedVersion ?? "no fix"}
            </dd>
          </>
        ) : null}
        {sub.cwePhrase ? (
          <>
            <dt>Weakness</dt>
            <dd>{sub.cwePhrase}</dd>
          </>
        ) : null}
        {sub.secretPreview ? (
          <>
            <dt>Secret preview</dt>
            <dd>
              <code>{sub.secretPreview}…</code> (first 4 chars only)
            </dd>
          </>
        ) : null}
        {sub.epss != null ? (
          <>
            <dt>EPSS</dt>
            <dd data-testid="epss">
              {(sub.epss * 100).toFixed(1)}%
              <span className="attribution" data-testid="epss-attribution">
                {" "}
                {sub.epssAttribution}
              </span>
            </dd>
          </>
        ) : null}
      </dl>

      {sub.scoreBreakdown && sub.scoreBreakdown.length ? (
        <>
          <button className="disclosure" onClick={() => setShowBreakdown((v) => !v)}>
            {showBreakdown ? "Hide why this score" : "Why this score"}
          </button>
          {showBreakdown ? (
            <ul className="breakdown" data-testid="why-score">
              {sub.scoreBreakdown.map((l, i) => (
                <li key={i}>
                  <span>{l.detail}</span>
                  <span>{l.delta >= 0 ? `+${l.delta}` : l.delta}</span>
                </li>
              ))}
            </ul>
          ) : null}
        </>
      ) : null}

      {fileLink || commitLink ? (
        <div className="source-links" data-testid="source-links">
          {fileLink ? (
            <a href={fileLink} target="_blank" rel="noopener noreferrer" data-testid="open-file-link">
              <Icon name="external" /> Open file at scanned commit
            </a>
          ) : null}
          {commitLink ? (
            <a href={commitLink} target="_blank" rel="noopener noreferrer" data-testid="view-commit-link">
              <Icon name="external" /> View commit
            </a>
          ) : null}
        </div>
      ) : null}

      <div className="location-actions">
        {sub.status === "open" ? (
          <Button variant="secondary" size="sm" onClick={() => setShowIgnore(true)}>
            Ignore…
          </Button>
        ) : sub.status === "ignored" ? (
          <div className="stack" data-testid="ignored-block">
            <div className="row">
              <span className="chip chip-ignored">Ignored</span>
              <span className="muted" title={sub.ignoreReason ?? undefined}>
                {reasonLabel(sub.ignoreReason)}
              </span>
            </div>
            {isSecscanYml ? (
              <p className="muted" data-testid="secscanyml-msg">
                Defined in the repository's <code>.secscan.yml</code>. Edit <code>.secscan.yml</code> in the repo to
                change it.
              </p>
            ) : heldByRule ? (
              <>
                <p className="muted">
                  Rule scope <strong>{sub.ruleScope}</strong>
                  {sub.ruleReason ? ` · "${sub.ruleReason}"` : ""}
                  {sub.ruleCreatedBy ? ` · by ${sub.ruleCreatedBy}` : ""}
                </p>
                {unignore.error ? <ErrorBanner error={unignore.error} /> : null}
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => (sub.ruleGlobal ? setConfirmUnignore(true) : unignore.mutate())}
                  disabled={unignore.isPending}
                  data-testid="unignore"
                >
                  {unignore.isPending ? "Unignoring…" : "Unignore (remove rule)"}
                </Button>
              </>
            ) : (
              <p className="muted" data-testid="autocause-msg">
                Auto-ignored by its cause ({sub.ignoreReason}). It clears automatically when the cause no longer
                applies — at the next scan, or when the relevant setting changes. There is no manual unignore.
              </p>
            )}
          </div>
        ) : (
          <div className="muted">
            Solved <RelativeTime iso={sub.solvedAt} />
          </div>
        )}
        <button className="disclosure" onClick={() => setShowHistory((v) => !v)}>
          {showHistory ? "Hide history" : "History"}
        </button>
      </div>
      {showHistory ? <History subId={sub.id} /> : null}

      {showIgnore ? <IgnoreDialog sub={sub} groupId={groupId} onClose={() => setShowIgnore(false)} /> : null}

      {confirmUnignore ? (
        <ConfirmDialog
          title="Unignore across every repository?"
          confirmLabel={unignore.isPending ? "Unignoring…" : "Unignore everywhere"}
          busy={unignore.isPending}
          onConfirm={() => unignore.mutate()}
          onCancel={() => setConfirmUnignore(false)}
        >
          <p>
            This ignore is a <strong>global</strong> rule (<code>{sub.ruleScope}</code>, all repositories). Removing
            it re-opens this finding everywhere the rule applied, not just here.
          </p>
        </ConfirmDialog>
      ) : null}
    </div>
  );
}

// A triage event in plain words. A BRANCH_CHANGED close/reopen (a branch switch, not a fix) reads as such and
// names the branch now scanned (D-076); everything else shows the status transition and its reason code.
function eventLabel(e: TriageEventView): string {
  if (e.reasonCode === "BRANCH_CHANGED") {
    const verb = e.toStatus === "solved" ? "Closed" : "Reopened";
    return `${verb} — branch changed${e.branch ? ` (now scanning ${e.branch})` : ""}`;
  }
  const base = `${e.fromStatus ?? "—"} → ${titleCase(e.toStatus ?? "—")}`;
  return e.reasonCode ? `${base} (${e.reasonCode})` : base;
}

function History({ subId }: { subId: string }) {
  const q = useQuery({
    queryKey: qk.subIssueEvents(subId, 0),
    queryFn: () => api.listSubIssueEvents(subId, 0, 50),
  });
  if (q.isLoading) {
    return <p className="muted">Loading history…</p>;
  }
  if (!q.data || q.data.content.length === 0) {
    return <p className="muted">No status changes yet.</p>;
  }
  return (
    <ul className="breakdown" data-testid="history">
      {q.data.content.map((e) => (
        <li key={e.id}>
          <span>
            {eventLabel(e)} · {e.actor}
          </span>
          <span className="muted">
            <RelativeTime iso={e.at} />
          </span>
        </li>
      ))}
    </ul>
  );
}
