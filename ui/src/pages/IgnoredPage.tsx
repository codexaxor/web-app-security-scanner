import { useState } from "react";
import { useSearchParams } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, type IgnoreRuleView, type IgnoredItemView } from "../api";
import { invalidateFor, qk } from "../queryClient";
import Pager, { useUrlPaging } from "../components/Pager";
import { reasonLabel, humanizeRuleId, typeLabel } from "../lib/labels";
import RelativeTime from "../components/RelativeTime";
import InlineError from "../components/InlineError";
import { useToast, errorMessage } from "../toast";
import Button from "../components/Button";
import ConfirmDialog from "../components/ConfirmDialog";
import TypeBadge from "../components/TypeBadge";
import { EmptyState, PageHeader, PathText } from "../components/ui";
import { TableSkeleton } from "../components/Skeleton";
import CreateRuleDialog, { type RulePrefill } from "../components/CreateRuleDialog";


function findingTitle(it: IgnoredItemView): string {
  if (it.vulnId) return it.vulnId;
  if (it.ruleId) return it.type === "sast" ? humanizeRuleId(it.ruleId) : it.ruleId;
  return it.packageName ?? typeLabel(it.type);
}

function ignoredBy(createdBy: string | null): string {
  if (!createdBy) return "System";
  if (createdBy === "secscan.yml") return "Repo file .secscan.yml";
  return createdBy;
}

export default function IgnoredPage() {
  const itemsPaging = useUrlPaging("page", "size");
  const rulesPaging = useUrlPaging("rulesPage", "rulesSize");
  const [sp, setSp] = useSearchParams();
  const reason = sp.get("reason") || undefined;
  const [prefill, setPrefill] = useState<RulePrefill | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<IgnoreRuleView | null>(null);
  const toast = useToast();

  const clearReason = () => {
    const next = new URLSearchParams(sp);
    next.delete("reason");
    next.delete("page");
    setSp(next, { replace: true });
  };

  const items = useQuery({
    queryKey: [...qk.ignored(itemsPaging.page, itemsPaging.size), reason ?? ""],
    queryFn: () => api.listIgnored(itemsPaging.page, itemsPaging.size, reason),
  });
  const rules = useQuery({
    queryKey: qk.ignoreRules(rulesPaging.page, rulesPaging.size),
    queryFn: () => api.listIgnoreRules(rulesPaging.page, rulesPaging.size),
  });

  const removeRule = useMutation({
    mutationFn: (id: string) => api.deleteIgnoreRule(id),
    onSuccess: () => {
      setConfirmDelete(null);
      toast.success("Ignore rule deleted.");
      return invalidateFor("triage");
    },
    onError: (e) => toast.error(errorMessage(e, "Could not delete the ignore rule.")),
  });

  return (
    <section>
      <PageHeader title="Ignored" subtitle="Findings held out of the feed, and the rules that hold them." />

      <h2 className="section-head">Ignored findings</h2>
      {reason ? (
        <div className="filter-chip" data-testid="reason-filter">
          <span>
            Filtered by reason: <strong>{reasonLabel(reason)}</strong>
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={clearReason}>
            Clear filter
          </button>
        </div>
      ) : null}
      {items.error ? <InlineError error={items.error} /> : null}
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
            {items.isLoading ? (
              <TableSkeleton columns={4} />
            ) : (
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
            )}
          </table>
        </div>
      )}
      <Pager
        page={itemsPaging.page}
        size={itemsPaging.size}
        totalElements={items.data?.totalElements ?? 0}
        totalPages={items.data?.totalPages ?? 1}
        onPage={itemsPaging.setPage}
        onSize={itemsPaging.setSize}
      />

      <h2 className="section-head">Ignore rules</h2>
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
      <Pager
        page={rulesPaging.page}
        size={rulesPaging.size}
        totalElements={rules.data?.totalElements ?? 0}
        totalPages={rules.data?.totalPages ?? 1}
        onPage={rulesPaging.setPage}
        onSize={rulesPaging.setSize}
      />

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
