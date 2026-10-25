import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type IssueGroupView, type SubIssueView, type TriageEventView } from "../api";
import { invalidateFor, qk } from "../queryClient";
import { advisoryLink, titleCase } from "../lib/format";
import { humanizeRuleId, reasonLabel, typeLabel } from "../lib/labels";
import RelativeTime from "./RelativeTime";
import ErrorBanner from "./ErrorBanner";
import IgnoreDialog from "./IgnoreDialog";
import ConfirmDialog from "./ConfirmDialog";
import Dialog from "./Dialog";
import Button from "./Button";
import SeverityPill from "./SeverityPill";
import TypeBadge from "./TypeBadge";
import RepoName from "./RepoName";

/**
 * Remediation line per issue type, from existing server data only. For SCA the fix comes from the group's
 * server-computed distinct `fixedVersions` over OPEN sub-issues (SF-7): one → "upgrade to <v>", several →
 * "see each location", none → no fix line at all (returns null). Other types keep their fixed guidance.
 */
function remediation(group: IssueGroupView | undefined, subs: SubIssueView[]): string | null {
  const type = group?.type ?? subs[0]?.type;
  if (type === "sca") {
    const pkg = group?.title ?? subs[0]?.packageName ?? "the dependency";
    const fixed = group?.fixedVersions ?? [];
    if (fixed.length === 1) return `Fix: upgrade ${pkg} to ${fixed[0]}`;
    if (fixed.length > 1) return "Fix: several fixed versions — see each location below.";
    return null; // no fixed version available → no fix line (SF-7)
  }
  if (type === "secret") return "Fix: rotate the exposed secret and remove it from the repository history.";
  if (type === "iac") return "Fix: correct the flagged infrastructure configuration.";
  if (type === "eol") return "Fix: upgrade to a supported runtime version.";
  return "Fix: review and remediate the flagged code.";
}

/** The full issue side panel (S6/S10d): a group header (title, severity, type, repo, remediation, affected
 *  locations, Ignore group…) then a compact locations list, each with its SPEC fields, triage and history. */
export default function SubIssuePanel({ groupId, onClose }: { groupId: string; onClose: () => void }) {
  const group = useQuery({ queryKey: qk.group(groupId), queryFn: () => api.getGroup(groupId) });
  const q = useQuery({
    queryKey: qk.subIssues(groupId, 0),
    queryFn: () => api.listSubIssues(groupId, 0, 100),
  });
  const [ignoreGroup, setIgnoreGroup] = useState(false);

  const subs = q.data?.content ?? [];
  const g = group.data;
  const title = g ? (g.type === "sast" ? humanizeRuleId(g.title) : g.title) : "Issue";
  // N-3: the affected-locations count is the server total, not subs.length (which caps at the 100-row page).
  const affected = q.data?.totalElements ?? subs.length;
  const fixLine = g ? remediation(g, subs) : null;

  return (
    <Dialog title="Issue details" onClose={onClose} variant="drawer">
      <div className="panel-header">
        <div className="row-between">
          <div className="row">
            {g ? <TypeBadge type={g.type} /> : null}
            {g ? <SeverityPill score={g.severityScore} /> : null}
          </div>
          <button className="panel-close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        <h2 className="panel-title">{title}</h2>
        {g ? (
          <div className="muted panel-sub">
            <RepoName path={g.repositoryPath} showPath />
            {g.repositoryBranch ? ` · ${g.repositoryBranch}` : ""} · {typeLabel(g.type)}
          </div>
        ) : null}
      </div>

      <div className="panel-body">
        {group.error ? <ErrorBanner error={group.error} /> : null}
        {q.error ? <ErrorBanner error={q.error} /> : null}

        {g ? (
          <div className="group-head">
            {fixLine ? <div className="remediation">{fixLine}</div> : null}
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
        {subs.map((s) => (
          <LocationCard key={s.id} sub={s} groupId={groupId} />
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

function LocationCard({ sub, groupId }: { sub: SubIssueView; groupId: string }) {
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
          {sub.filePath ? (
            <div className="file-path muted" title={sub.filePath}>
              {sub.filePath}
              {sub.line != null ? `:${sub.line}` : ""}
            </div>
          ) : null}
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
            {showBreakdown ? "Hide score breakdown" : "Score breakdown"}
          </button>
          {showBreakdown ? (
            <ul className="breakdown">
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
