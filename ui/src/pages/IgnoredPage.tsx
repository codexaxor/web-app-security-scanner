import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type IgnoreRuleView, type IgnoredItemView } from "../api";
import { invalidateFor, qk } from "../queryClient";
import { reasonLabel, humanizeRuleId, typeShort } from "../lib/labels";
import RelativeTime from "../components/RelativeTime";
import ErrorBanner from "../components/ErrorBanner";
import Button from "../components/Button";
import ConfirmDialog from "../components/ConfirmDialog";
import TypeBadge from "../components/TypeBadge";
import { EmptyState, PageHeader, PathText } from "../components/ui";
import CreateRuleDialog, { type RulePrefill } from "../components/CreateRuleDialog";

const SIZE = 50;

function findingTitle(it: IgnoredItemView): string {
  if (it.vulnId) return it.vulnId;
  if (it.ruleId) return it.type === "sast" ? humanizeRuleId(it.ruleId) : it.ruleId;
  return it.packageName ?? typeShort(it.type);
}

function ignoredBy(createdBy: string | null): string {
  if (!createdBy) return "System";
  if (createdBy === "secscan.yml") return "Repo file .secscan.yml";
  return createdBy;
}

export default function IgnoredPage() {
  const [itemsPage, setItemsPage] = useState(0);
  const [rulesPage, setRulesPage] = useState(0);
  const [prefill, setPrefill] = useState<RulePrefill | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<IgnoreRuleView | null>(null);

  const items = useQuery({ queryKey: qk.ignored(itemsPage), queryFn: () => api.listIgnored(itemsPage, SIZE) });
  const rules = useQuery({ queryKey: qk.ignoreRules(rulesPage), queryFn: () => api.listIgnoreRules(rulesPage, SIZE) });

  const removeRule = useMutation({
    mutationFn: (id: string) => api.deleteIgnoreRule(id),
    onSuccess: () => {
      setConfirmDelete(null);
      return invalidateFor("triage");
    },
  });

  return (
    <section>
      <PageHeader title="Ignored" subtitle="Findings held out of the feed, and the rules that hold them." />

      <h2 className="section-head">Ignored findings</h2>
      {items.error ? <ErrorBanner error={items.error} /> : null}
      {items.data && items.data.content.length === 0 ? (
        <EmptyState title="Nothing is ignored">Everything found is in the feed.</EmptyState>
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Finding</th>
                <th>Reason</th>
                <th>Ignored by</th>
                <th>When</th>
              </tr>
            </thead>
            <tbody>
              {items.data?.content.map((it) => (
                <tr key={it.subIssueId} data-testid="ignored-item">
                  <td>
                    <div className="row">
                      <TypeBadge type={it.type} />
                      <strong>{findingTitle(it)}</strong>
                    </div>
                    {it.filePath ? (
                      <div className="muted">
                        <PathText path={it.filePath} line={it.line} />
                      </div>
                    ) : null}
                  </td>
                  <td>
                    <span title={it.reasonCode ?? undefined}>{reasonLabel(it.reasonCode)}</span>
                    {it.ruleReason ? <div className="muted">{it.ruleReason}</div> : null}
                  </td>
                  <td>{ignoredBy(it.createdBy)}</td>
                  <td data-testid="ignored-at">
                    <RelativeTime iso={it.ignoredAt} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pager page={itemsPage} totalPages={items.data?.totalPages ?? 1} onPage={setItemsPage} />

      <h2 className="section-head">Ignore rules</h2>
      {removeRule.error ? <ErrorBanner error={removeRule.error} onDismiss={() => removeRule.reset()} /> : null}
      {rules.data && rules.data.content.length === 0 ? (
        <EmptyState title="No manual ignore rules">
          Ignore a finding from its side panel to create a rule.
        </EmptyState>
      ) : (
        <div className="table-wrap">
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
              {rules.data?.content.map((r: IgnoreRuleView) => {
                const managed = r.createdBy === "secscan.yml";
                return (
                  <tr key={r.id} data-testid="ignore-rule">
                    <td>
                      <span className="type-badge">{r.scope}</span>
                    </td>
                    <td>
                      <code>{r.matchValue}</code>
                    </td>
                    <td>{r.reason}</td>
                    <td>
                      {r.repositoryId ? "this repo" : <span className="muted">all repos</span>}
                    </td>
                    <td>{managed ? "Repo file .secscan.yml" : r.createdBy}</td>
                    <td>
                      {managed ? (
                        <span
                          className="muted"
                          data-testid="managed-rule"
                          title="This rule is defined in the repository's .secscan.yml. Edit that file in the repo to change it."
                        >
                          Managed in repo file
                        </span>
                      ) : (
                        <div className="row">
                          <Button
                            variant="danger"
                            size="sm"
                            onClick={() => setConfirmDelete(r)}
                            data-testid="delete-rule"
                          >
                            Delete
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
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
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <Pager page={rulesPage} totalPages={rules.data?.totalPages ?? 1} onPage={setRulesPage} />

      {confirmDelete ? (
        <ConfirmDialog
          title="Delete ignore rule"
          confirmLabel="Delete rule"
          confirmVariant="danger"
          busy={removeRule.isPending}
          onConfirm={() => removeRule.mutate(confirmDelete.id)}
          onCancel={() => setConfirmDelete(null)}
        >
          <p>
            Delete the <strong>{confirmDelete.scope}</strong> rule <code>{confirmDelete.matchValue}</code>?
          </p>
          <p className="muted">Any findings it currently holds will return to the feed on the next reconcile.</p>
        </ConfirmDialog>
      ) : null}

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
      <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
        Previous
      </button>
      <span>
        Page {page + 1} of {totalPages}
      </span>
      <button className="btn btn-secondary btn-sm" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
        Next
      </button>
    </div>
  );
}
