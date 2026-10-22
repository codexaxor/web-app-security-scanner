import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type IgnoreRuleView } from "../api";
import { invalidateFor, qk } from "../queryClient";
import RelativeTime from "../components/RelativeTime";
import ErrorBanner from "../components/ErrorBanner";
import CreateRuleDialog, { type RulePrefill } from "../components/CreateRuleDialog";

const SIZE = 50;

export default function IgnoredPage() {
  const [itemsPage, setItemsPage] = useState(0);
  const [rulesPage, setRulesPage] = useState(0);
  const [prefill, setPrefill] = useState<RulePrefill | null>(null);

  const items = useQuery({
    queryKey: qk.ignored(itemsPage),
    queryFn: () => api.listIgnored(itemsPage, SIZE),
  });
  const rules = useQuery({
    queryKey: qk.ignoreRules(rulesPage),
    queryFn: () => api.listIgnoreRules(rulesPage, SIZE),
  });

  const removeRule = useMutation({
    mutationFn: (id: string) => api.deleteIgnoreRule(id),
    onSuccess: () => invalidateFor("triage"),
  });

  return (
    <section>
      <h1>Ignored</h1>

      <h2>Ignored findings</h2>
      {items.error ? <ErrorBanner error={items.error} /> : null}
      <table className="table">
        <thead>
          <tr>
            <th>Finding</th>
            <th>Reason</th>
            <th>Source</th>
            <th>Ignored</th>
          </tr>
        </thead>
        <tbody>
          {items.data?.content.map((it) => (
            <tr key={it.subIssueId} data-testid="ignored-item">
              <td>
                <strong>{it.vulnId ?? it.ruleId ?? it.packageName ?? it.type}</strong>
                {it.filePath ? <div className="muted">{it.filePath}</div> : null}
              </td>
              <td>
                <span className="badge sev-ignored">{it.reasonCode}</span>
                {it.ruleReason ? <div className="muted">{it.ruleReason}</div> : null}
                {it.ruleScope ? <div className="muted">scope: {it.ruleScope}</div> : null}
              </td>
              <td>{it.createdBy ?? "auto"}</td>
              <td data-testid="ignored-at">
                <RelativeTime iso={it.ignoredAt} />
              </td>
            </tr>
          ))}
          {items.data && items.data.content.length === 0 ? (
            <tr>
              <td colSpan={4} className="muted">
                Nothing is ignored.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
      <Pager page={itemsPage} totalPages={items.data?.totalPages ?? 1} onPage={setItemsPage} />

      <h2 style={{ marginTop: "1.5rem" }}>Ignore rules</h2>
      {removeRule.error ? <ErrorBanner error={removeRule.error} onDismiss={() => removeRule.reset()} /> : null}
      <table className="table">
        <thead>
          <tr>
            <th>Scope</th>
            <th>Match value</th>
            <th>Reason</th>
            <th>Applies to</th>
            <th>Created by</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {rules.data?.content.map((r: IgnoreRuleView) => (
            <tr key={r.id} data-testid="ignore-rule">
              <td>{r.scope}</td>
              <td>
                <code>{r.matchValue}</code>
              </td>
              <td>{r.reason}</td>
              <td>{r.repositoryId ? "this repo" : "all repos"}</td>
              <td>{r.createdBy}</td>
              <td>
                <div className="row">
                  <button
                    onClick={() => removeRule.mutate(r.id)}
                    disabled={removeRule.isPending}
                    data-testid="delete-rule"
                  >
                    Delete
                  </button>
                  <button
                    onClick={() =>
                      setPrefill({
                        scope: r.scope,
                        repositoryId: r.repositoryId,
                        matchValue: r.matchValue,
                        reason: r.reason,
                      })
                    }
                  >
                    Create new…
                  </button>
                </div>
              </td>
            </tr>
          ))}
          {rules.data && rules.data.content.length === 0 ? (
            <tr>
              <td colSpan={6} className="muted">
                No manual ignore rules.
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
      <Pager page={rulesPage} totalPages={rules.data?.totalPages ?? 1} onPage={setRulesPage} />

      {prefill ? <CreateRuleDialog prefill={prefill} onClose={() => setPrefill(null)} /> : null}
    </section>
  );
}

function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) {
    return null;
  }
  return (
    <div className="pager">
      <button disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <span className="muted">
        Page {page + 1} of {totalPages}
      </span>
      <button disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </div>
  );
}
