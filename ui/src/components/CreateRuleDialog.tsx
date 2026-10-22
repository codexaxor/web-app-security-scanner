import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api } from "../api";
import { invalidateFor } from "../queryClient";
import ErrorBanner from "./ErrorBanner";

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
    <div className="overlay overlay-center" onClick={onClose}>
      <div className="modal" role="dialog" aria-label="Create ignore rule" onClick={(e) => e.stopPropagation()}>
        <h2>Create ignore rule</h2>
        {create.error ? <ErrorBanner error={create.error} /> : null}
        <div className="stack">
          <label>
            Scope
            <select value={scope} onChange={(e) => setScope(e.target.value)}>
              <option value="path">Path prefix</option>
              <option value="cve">CVE</option>
              <option value="rule">Rule id</option>
            </select>
          </label>
          <label>
            Match value
            <input value={matchValue} onChange={(e) => setMatchValue(e.target.value)} />
            {scope === "path" ? (
              <small className="muted">A repo-relative path prefix (matches this path and everything under it).</small>
            ) : null}
          </label>
          <label>
            Reason (required)
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} required />
          </label>
          <label className="row">
            <input type="checkbox" checked={global} onChange={(e) => setGlobal(e.target.checked)} />
            Global — apply across every repository
          </label>
          {global ? (
            <small className="muted">This rule applies to every repository in the workspace.</small>
          ) : null}
        </div>
        <div className="row" style={{ justifyContent: "flex-end", marginTop: "0.8rem" }}>
          <button onClick={onClose} disabled={create.isPending}>
            Cancel
          </button>
          <button className="primary" onClick={() => create.mutate()} disabled={!canSubmit}>
            Create rule
          </button>
        </div>
      </div>
    </div>
  );
}
