import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type SubIssueView } from "../api";
import { invalidateFor, qk } from "../queryClient";
import { advisoryLink, titleCase } from "../lib/format";
import RelativeTime from "./RelativeTime";
import ErrorBanner from "./ErrorBanner";
import IgnoreDialog from "./IgnoreDialog";

/** The full issue side panel (S6): every sub-issue's SPEC fields, ignore at 5 scopes, unignore (delete the
 *  holding manual rule), the .secscan.yml 409 message, the auto-ignore explanation, and per-sub-issue history. */
export default function SubIssuePanel({ groupId, onClose }: { groupId: string; onClose: () => void }) {
  const q = useQuery({
    queryKey: qk.subIssues(groupId, 0),
    queryFn: () => api.listSubIssues(groupId, 0, 100),
  });

  return (
    <div className="overlay" onClick={onClose}>
      <aside className="panel" role="dialog" aria-label="Issue details" onClick={(e) => e.stopPropagation()}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2>Issue details</h2>
          <button onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>
        {q.error ? <ErrorBanner error={q.error} /> : null}
        {q.isLoading ? <p className="muted">Loading…</p> : null}
        {q.data?.content.map((s) => (
          <SubIssueCard key={s.id} sub={s} groupId={groupId} />
        ))}
      </aside>
    </div>
  );
}

function SubIssueCard({ sub, groupId }: { sub: SubIssueView; groupId: string }) {
  const [showIgnore, setShowIgnore] = useState(false);
  const [showHistory, setShowHistory] = useState(false);

  const unignore = useMutation({
    mutationFn: () => api.deleteIgnoreRule(sub.ignoreRuleId as string),
    onSuccess: () => invalidateFor("triage"),
  });

  const link = advisoryLink(sub.vulnId);
  const isSecscanYml = sub.ruleCreatedBy === "secscan.yml";
  const heldByRule = sub.ignoreRuleId != null;

  return (
    <div className="subissue" data-testid="subissue">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <strong>
          {link ? (
            <a href={link} target="_blank" rel="noreferrer">
              {sub.vulnId}
            </a>
          ) : (
            sub.vulnId ?? sub.ruleId ?? sub.packageName ?? sub.type
          )}
        </strong>
        <span className="row" style={{ gap: "0.3rem" }}>
          {sub.inKev ? <span className="badge badge-kev" data-testid="kev-badge">KEV</span> : null}
          {sub.score != null ? <span className="badge sev-high">{sub.score}</span> : null}
        </span>
      </div>

      <dl className="kv">
        {sub.packageName ? (
          <>
            <dt>Package</dt>
            <dd>{sub.packageName}</dd>
          </>
        ) : null}
        {sub.installedVersion || sub.fixedVersion ? (
          <>
            <dt>Version</dt>
            <dd>
              {sub.installedVersion ?? "?"} → {sub.fixedVersion ?? "no fix"}
            </dd>
          </>
        ) : null}
        {sub.filePath ? (
          <>
            <dt>Location</dt>
            <dd>
              {sub.filePath}
              {sub.line != null ? `:${sub.line}` : ""}
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
              <div className="muted" data-testid="epss-attribution">
                {sub.epssAttribution}
              </div>
            </dd>
          </>
        ) : null}
      </dl>

      {sub.scoreBreakdown && sub.scoreBreakdown.length ? (
        <>
          <div className="muted">Score breakdown</div>
          <ul className="breakdown">
            {sub.scoreBreakdown.map((l, i) => (
              <li key={i}>
                <span>{l.detail}</span>
                <span>{l.delta >= 0 ? `+${l.delta}` : l.delta}</span>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {/* status + triage actions */}
      {sub.status === "open" ? (
        <button onClick={() => setShowIgnore(true)}>Ignore…</button>
      ) : sub.status === "ignored" ? (
        <div className="stack" data-testid="ignored-block">
          <div>
            Ignored — <span className="badge sev-ignored">{sub.ignoreReason}</span>
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
              <button onClick={() => unignore.mutate()} disabled={unignore.isPending} data-testid="unignore">
                {unignore.isPending ? "Unignoring…" : "Unignore (remove rule)"}
              </button>
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

      <div style={{ marginTop: "0.4rem" }}>
        <button className="badge badge-inactive" onClick={() => setShowHistory((v) => !v)}>
          {showHistory ? "Hide history" : "History"}
        </button>
        {showHistory ? <History subId={sub.id} /> : null}
      </div>

      {showIgnore ? <IgnoreDialog sub={sub} groupId={groupId} onClose={() => setShowIgnore(false)} /> : null}
    </div>
  );
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
            {e.fromStatus ?? "—"} → {titleCase(e.toStatus ?? "—")}
            {e.reasonCode ? ` (${e.reasonCode})` : ""} · {e.actor}
          </span>
          <span className="muted">
            <RelativeTime iso={e.at} />
          </span>
        </li>
      ))}
    </ul>
  );
}
