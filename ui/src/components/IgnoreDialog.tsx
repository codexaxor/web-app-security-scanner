import { useMemo, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type SubIssueView } from "../api";
import { invalidateFor } from "../queryClient";
import InlineError from "./InlineError";
import Dialog from "./Dialog";
import Button from "./Button";

/**
 * Ignore one finding at one of the 5 SPEC scopes (D-058). A reason is required (also enforced server-side).
 * Global scopes (CVE/rule across every repository) need an explicit confirm. The match_value formats mirror
 * core.triage.IgnoreMatcher (change 8): path = a repo-relative path PREFIX (matches the path and everything
 * under it), cve = a CVE id, rule = a rule id; subissue/group compute their own value server-side.
 */
type ScopeKey =
  | "subissue"
  | "group"
  | "path-repo"
  | "cve-repo"
  | "cve-global"
  | "rule-repo"
  | "rule-global";

interface ScopeOption {
  key: ScopeKey;
  label: string;
  global: boolean;
  needsValue: boolean;
  initialValue?: string;
  hint?: string;
}

export default function IgnoreDialog({
  sub,
  groupId,
  onClose,
  defaultScope = "subissue",
}: {
  sub: SubIssueView;
  groupId: string;
  onClose: () => void;
  defaultScope?: ScopeKey;
}) {
  const options = useMemo<ScopeOption[]>(() => {
    const opts: ScopeOption[] = [
      { key: "subissue", label: "This finding", global: false, needsValue: false },
      { key: "group", label: "This whole issue group", global: false, needsValue: false },
    ];
    if (sub.filePath) {
      opts.push({
        key: "path-repo",
        label: "Path prefix (this repo)",
        global: false,
        needsValue: true,
        initialValue: sub.filePath,
        hint: "Matches this path and everything under it (a repo-relative prefix, not a glob).",
      });
    }
    if (sub.vulnId) {
      opts.push({ key: "cve-repo", label: "This CVE (this repo)", global: false, needsValue: true, initialValue: sub.vulnId });
      opts.push({ key: "cve-global", label: "This CVE (all repos)", global: true, needsValue: true, initialValue: sub.vulnId });
    }
    if (sub.ruleId) {
      opts.push({ key: "rule-repo", label: "This rule (this repo)", global: false, needsValue: true, initialValue: sub.ruleId });
      opts.push({ key: "rule-global", label: "This rule (all repos)", global: true, needsValue: true, initialValue: sub.ruleId });
    }
    return opts;
  }, [sub]);

  const [scope, setScope] = useState<ScopeKey>(
    options.some((o) => o.key === defaultScope) ? defaultScope : "subissue",
  );
  const current = options.find((o) => o.key === scope)!;
  const [value, setValue] = useState(current.initialValue ?? "");
  const [reason, setReason] = useState("");
  const [confirmGlobal, setConfirmGlobal] = useState(false);

  function onScopeChange(key: ScopeKey) {
    setScope(key);
    const next = options.find((o) => o.key === key)!;
    setValue(next.initialValue ?? "");
    setConfirmGlobal(false);
  }

  const submit = useMutation({
    mutationFn: async () => {
      switch (scope) {
        case "subissue":
          return api.ignoreSubIssue(sub.id, reason);
        case "group":
          return api.ignoreGroup(groupId, reason);
        case "path-repo":
          return api.createIgnoreRule({ scope: "path", repositoryId: sub.repositoryId, matchValue: value.trim(), reason });
        case "cve-repo":
          return api.createIgnoreRule({ scope: "cve", repositoryId: sub.repositoryId, matchValue: value.trim(), reason });
        case "cve-global":
          return api.createIgnoreRule({ scope: "cve", repositoryId: null, matchValue: value.trim(), reason });
        case "rule-repo":
          return api.createIgnoreRule({ scope: "rule", repositoryId: sub.repositoryId, matchValue: value.trim(), reason });
        case "rule-global":
          return api.createIgnoreRule({ scope: "rule", repositoryId: null, matchValue: value.trim(), reason });
      }
    },
    onSuccess: async () => {
      await invalidateFor("triage");
      onClose();
    },
  });

  const reasonOk = reason.trim().length > 0;
  const valueOk = !current.needsValue || value.trim().length > 0;
  const globalOk = !current.global || confirmGlobal;
  const canSubmit = reasonOk && valueOk && globalOk && !submit.isPending;

  return (
    <Dialog title="Ignore finding" onClose={onClose} variant="center">
      <h2>Ignore finding</h2>
      {submit.error ? <InlineError error={submit.error} variant="field" /> : null}
      <div className="form">
        <div className="field">
          <label htmlFor="ignore-scope">Scope</label>
          <select
            id="ignore-scope"
            aria-label="Ignore scope"
            value={scope}
            onChange={(e) => onScopeChange(e.target.value as ScopeKey)}
          >
            {options.map((o) => (
              <option key={o.key} value={o.key}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
        {current.needsValue ? (
          <div className="field">
            <label htmlFor="ignore-value">Match value</label>
            <input id="ignore-value" value={value} onChange={(e) => setValue(e.target.value)} />
            {current.hint ? <span className="help">{current.hint}</span> : null}
          </div>
        ) : null}
        <div className="field">
          <label htmlFor="ignore-reason">Reason (required)</label>
          <textarea id="ignore-reason" value={reason} onChange={(e) => setReason(e.target.value)} rows={2} required />
          {!reasonOk ? <span className="field-error">A reason is required.</span> : null}
        </div>
        {current.global ? (
          <label className="check">
            <input type="checkbox" checked={confirmGlobal} onChange={(e) => setConfirmGlobal(e.target.checked)} />
            I understand this applies across <strong>&nbsp;every repository&nbsp;</strong> in the workspace.
          </label>
        ) : null}
      </div>
      <div className="modal-actions">
        <Button variant="secondary" onClick={onClose} disabled={submit.isPending}>
          Cancel
        </Button>
        <Button variant="primary" onClick={() => submit.mutate()} disabled={!canSubmit}>
          Ignore
        </Button>
      </div>
    </Dialog>
  );
}
