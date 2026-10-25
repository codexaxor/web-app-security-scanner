import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "../api";
import { invalidateFor } from "../queryClient";
import ErrorBanner from "./ErrorBanner";
import Dialog from "./Dialog";
import Button from "./Button";

/**
 * Create a generic ignore rule (path / cve / rule), prefilled from an existing rule (OD-16: "create new"
 * prefilled). Reason is required; global (all repos) is an explicit choice. There is no edit-in-place (D-069):
 * the Ignored view deletes the old rule and the user creates a tweaked new one here.
 */
export interface RulePrefill {
  scope: string;
  repositoryId: string | null;
  matchValue: string;
  reason: string;
}

export default function CreateRuleDialog({
  prefill,
  onClose,
}: {
  prefill: RulePrefill;
  onClose: () => void;
}) {
  const [scope, setScope] = useState(prefill.scope);
  const [matchValue, setMatchValue] = useState(prefill.matchValue);
  const [reason, setReason] = useState(prefill.reason);
  const [global, setGlobal] = useState(prefill.repositoryId == null);

  const create = useMutation({
    mutationFn: () =>
      api.createIgnoreRule({
        scope,
        repositoryId: global ? null : prefill.repositoryId,
        matchValue: matchValue.trim(),
        reason: reason.trim(),
      }),
    onSuccess: async () => {
      await invalidateFor("triage");
      onClose();
    },
  });

  const canSubmit = matchValue.trim() && reason.trim() && !create.isPending;

  return (
    <Dialog title="Create ignore rule" onClose={onClose} variant="center">
      <h2>Create ignore rule</h2>
      {create.error ? <ErrorBanner error={create.error} /> : null}
      <div className="form">
        <div className="field">
          <label htmlFor="rule-scope">Scope</label>
          <select id="rule-scope" value={scope} onChange={(e) => setScope(e.target.value)}>
            <option value="path">Path prefix</option>
            <option value="cve">CVE</option>
            <option value="rule">Rule id</option>
          </select>
        </div>
        <div className="field">
          <label htmlFor="rule-value">Match value</label>
          <input id="rule-value" value={matchValue} onChange={(e) => setMatchValue(e.target.value)} />
          {scope === "path" ? (
            <span className="help">A repo-relative path prefix (matches this path and everything under it).</span>
          ) : null}
        </div>
        <div className="field">
          <label htmlFor="rule-reason">Reason (required)</label>
          <textarea id="rule-reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={2} required />
        </div>
        <label className="check">
          <input type="checkbox" checked={global} onChange={(e) => setGlobal(e.target.checked)} />
          Global — apply across every repository
        </label>
        {global ? <span className="help">This rule applies to every repository in the workspace.</span> : null}
      </div>
      <div className="modal-actions">
        <Button variant="secondary" onClick={onClose} disabled={create.isPending}>
          Cancel
        </Button>
        <Button variant="primary" onClick={() => create.mutate()} disabled={!canSubmit}>
          Create rule
        </Button>
      </div>
    </Dialog>
  );
}
