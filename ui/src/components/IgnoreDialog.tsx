import { useMemo, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, type SubIssueView } from "../api";
import { invalidateFor } from "../queryClient";
import ErrorBanner from "./ErrorBanner";

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
}: {
  sub: SubIssueView;
  groupId: string;
  onClose: () => void;
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

  const [scope, setScope] = useState<ScopeKey>("subissue");
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
    <div className="overlay overlay-center" onClick={onClose}>
      <div className="modal" role="dialog" aria-label="Ignore finding" onClick={(e) => e.stopPropagation()}>
        <h2>Ignore finding</h2>
        {submit.error ? <ErrorBanner error={submit.error} /> : null}
        <div className="stack">
          <label>
            Scope
            <select aria-label="Ignore scope" value={scope} onChange={(e) => onScopeChange(e.target.value as ScopeKey)}>
              {options.map((o) => (
                <option key={o.key} value={o.key}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
          {current.needsValue ? (
            <label>
              Match value
              <input value={value} onChange={(e) => setValue(e.target.value)} />
              {current.hint ? <small className="muted">{current.hint}</small> : null}
            </label>
          ) : null}
          <label>
            Reason (required)
            <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} required />
          </label>
          {!reasonOk ? <small className="field-error">A reason is required.</small> : null}
          {current.global ? (
            <label className="row">
              <input type="checkbox" checked={confirmGlobal} onChange={(e) => setConfirmGlobal(e.target.checked)} />
              I understand this applies across <strong>every repository</strong> in the workspace.
            </label>
          ) : null}
        </div>
        <div className="row" style={{ justifyContent: "flex-end", marginTop: "0.8rem" }}>
          <button onClick={onClose} disabled={submit.isPending}>
            Cancel
          </button>
          <button className="primary" onClick={() => submit.mutate()} disabled={!canSubmit}>
            Ignore
          </button>
        </div>
      </div>
    </div>
  );
}
